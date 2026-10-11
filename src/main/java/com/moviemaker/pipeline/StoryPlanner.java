package com.moviemaker.pipeline;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns raw story text into a scene list. Extractive: scenes are contiguous slices of the story; when a slice is longer
 * than the narration budget for the chosen video length, the most informative sentences are kept (in original order).
 */
@Component
public class StoryPlanner {

    public record ScenePlan(int index, String title, String narration) {}

    public static final double WORDS_PER_MINUTE = 150;
    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for",
            "with", "as", "by", "from", "that", "this", "these", "those", "is", "was", "were", "are", "be", "been", "it",
            "its", "he", "she", "they", "them", "his", "her", "their", "we", "you", "i", "my", "our", "your", "had", "has",
            "have", "not", "no", "so", "if", "then", "than", "when", "which", "who", "whom", "what", "there", "also", "into",
            "up", "out", "over", "after", "before", "about", "would", "could", "will", "can", "do", "did", "does", "said",
            "him", "me", "us", "all", "one", "very", "more", "most", "some", "any", "each", "such", "only", "own", "same",
            "too", "just", "now", "how", "why", "where", "while", "during", "through", "again", "once", "here", "even");

    public static int sceneCountFor(int minutes) {
        if (minutes <= 1) return 3;
        if (minutes <= 3) return 5;
        if (minutes <= 5) return 6;
        if (minutes <= 10) return 8;
        if (minutes <= 15) return 10;
        return 14;
    }

    public List<ScenePlan> plan(String rawText, int lengthMinutes) {
        Parsed parsed = parse(rawText);
        List<String> sentences = parsed.sentences;
        if (sentences.isEmpty()) return List.of();

        int n = Math.max(1, Math.min(sceneCountFor(lengthMinutes), sentences.size()));
        int wordBudget = (int) Math.max(12, lengthMinutes * WORDS_PER_MINUTE * 0.92 / n);
        Map<String, Integer> globalFreq = frequencies(String.join(" ", sentences));

        List<ScenePlan> plans = new ArrayList<>();
        int total = sentences.size();
        for (int i = 0; i < n; i++) {
            int from = (int) Math.round((double) i * total / n);
            int to = (int) Math.round((double) (i + 1) * total / n);
            if (to <= from) to = Math.min(total, from + 1);
            List<String> slice = sentences.subList(from, to);
            List<String> chosen = fit(slice, globalFreq, wordBudget);
            String title = titleFor(i, n, from, to, parsed, chosen);
            plans.add(new ScenePlan(i, title, String.join(" ", chosen)));
        }
        return plans;
    }

    // ---- parsing -------------------------------------------------------------------------------------------------

    private record Parsed(List<String> sentences, TreeMap<Integer, String> headingsBySentence) {}

    private Parsed parse(String raw) {
        String text = raw.replace("\r", "").replace(' ', ' ').replace("\u0000", "");
        String[] lines = text.split("\n");
        StringBuilder body = new StringBuilder();
        TreeMap<Integer, String> headingOffsets = new TreeMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip().replaceAll("\\s+", " ");
            if (line.isEmpty()) {
                if (body.length() > 0 && body.charAt(body.length() - 1) != ' ') body.append(' ');
                continue;
            }
            if (looksLikeHeading(line) && i + 1 < lines.length) {
                headingOffsets.put(body.length(), line.replaceAll("^[#\\d.)\\s]+(?=\\p{L})", ""));
                continue;
            }
            body.append(line).append(' ');
        }
        String joined = body.toString().replaceAll("\\s+", " ").strip();
        List<String> sentences = new ArrayList<>();
        // Java's sentence iterator does not treat the Devanagari danda as a terminator, so use explicit boundaries.
        List<Integer> cuts = new ArrayList<>();
        cuts.add(0);
        if (joined.indexOf('।') >= 0) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<=[।.!?])\\s+").matcher(joined);
            while (m.find()) cuts.add(m.end());
            cuts.add(joined.length());
        } else {
            BreakIterator it = BreakIterator.getSentenceInstance(Locale.ENGLISH);
            it.setText(joined);
            for (int e = it.next(); e != BreakIterator.DONE; e = it.next()) cuts.add(e);
        }
        TreeMap<Integer, String> bySentence = new TreeMap<>();
        // headings were recorded against the pre-normalised buffer; map approximately by relative position
        double ratio = body.length() == 0 ? 0 : (double) joined.length() / body.length();
        List<int[]> bounds = new ArrayList<>();
        for (int ci = 1; ci < cuts.size(); ci++) {
            int start = cuts.get(ci - 1), end = cuts.get(ci);
            String s = joined.substring(start, end).strip();
            if (s.split("\\s+").length >= 2 || s.length() > 12) {
                sentences.add(s);
                bounds.add(new int[]{start, end});
            }
        }
        for (Map.Entry<Integer, String> h : headingOffsets.entrySet()) {
            int pos = (int) Math.round(h.getKey() * ratio);
            for (int s = 0; s < bounds.size(); s++) {
                if (pos < bounds.get(s)[1]) { bySentence.putIfAbsent(s, h.getValue()); break; }
            }
        }
        return new Parsed(sentences, bySentence);
    }

    private static boolean looksLikeHeading(String line) {
        int words = line.split(" ").length;
        if (words > 8 || line.length() < 3 || line.length() > 70) return false;
        char last = line.charAt(line.length() - 1);
        if (".!?,;:\"'”".indexOf(last) >= 0) return false;
        return Character.isUpperCase(line.codePointAt(0)) || Character.isDigit(line.charAt(0));
    }

    // ---- selection -----------------------------------------------------------------------------------------------

    private List<String> fit(List<String> slice, Map<String, Integer> freq, int wordBudget) {
        int words = slice.stream().mapToInt(StoryPlanner::wordCount).sum();
        if (words <= wordBudget) return new ArrayList<>(slice);

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slice.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> -score(slice.get(i), i, freq)));

        List<Integer> picked = new ArrayList<>();
        int used = 0;
        for (int idx : order) {
            int w = wordCount(slice.get(idx));
            if (used + w > wordBudget && !picked.isEmpty()) continue;
            picked.add(idx);
            used += w;
            if (used >= wordBudget) break;
        }
        picked.sort(Integer::compare);
        return picked.stream().map(slice::get).collect(Collectors.toCollection(ArrayList::new));
    }

    private double score(String sentence, int position, Map<String, Integer> freq) {
        List<String> toks = tokens(sentence);
        if (toks.isEmpty()) return 0;
        double sum = 0;
        for (String t : toks) sum += freq.getOrDefault(t, 0);
        double s = sum / Math.sqrt(toks.size());
        return position == 0 ? s * 1.5 : s;
    }

    // ---- titles --------------------------------------------------------------------------------------------------

    private String titleFor(int i, int n, int from, int to, Parsed parsed, List<String> chosen) {
        if (n >= 3 && i == 0) return "Introduction";
        if (n >= 3 && i == n - 1) return "Conclusion";
        Map.Entry<Integer, String> h = parsed.headingsBySentence.ceilingEntry(from);
        if (h != null && h.getKey() < to) return clip(h.getValue());
        Map<String, Integer> f = frequencies(String.join(" ", chosen));
        List<String> top = f.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .filter(e -> e.getKey().length() > 3)
                .limit(2).map(e -> capitalise(e.getKey())).toList();
        if (top.isEmpty()) return "Part " + (i + 1);
        return String.join(" ", top);
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 39).strip() + "…";
    }

    private static String capitalise(String w) {
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    private static Map<String, Integer> frequencies(String text) {
        Map<String, Integer> f = new HashMap<>();
        for (String t : tokens(text)) f.merge(t, 1, Integer::sum);
        return f;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^\\p{L}']+")) {
            if (t.length() > 2 && !STOP.contains(t)) out.add(t);
        }
        return out;
    }

    static int wordCount(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
