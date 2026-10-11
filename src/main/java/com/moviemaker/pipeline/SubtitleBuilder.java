package com.moviemaker.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds SRT captions for one scene, timing chunks proportionally to their length. */
public final class SubtitleBuilder {
    private static final int MAX_WORDS = 11;

    private SubtitleBuilder() {}

    public record Cue(double start, double end, String text) {}

    public static List<Cue> cues(String narration, double leadIn, double speechSeconds) {
        List<String> chunks = chunk(narration);
        int totalChars = chunks.stream().mapToInt(String::length).sum();
        List<Cue> cues = new ArrayList<>();
        if (totalChars == 0) return cues;
        double t = leadIn;
        for (String c : chunks) {
            double d = speechSeconds * c.length() / totalChars;
            cues.add(new Cue(t, t + d, c));
            t += d;
        }
        return cues;
    }

    public static String srt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Cue c : cues) {
            sb.append(i++).append('\n')
              .append(ts(c.start())).append(" --> ").append(ts(Math.max(c.end() - 0.05, c.start() + 0.2))).append('\n')
              .append(c.text()).append("\n\n");
        }
        return sb.toString();
    }

    static List<String> chunk(String narration) {
        List<String> out = new ArrayList<>();
        for (String sentence : narration.strip().split("(?<=[.!?।])\\s+")) {
            String[] words = sentence.strip().split("\\s+");
            if (words.length == 1 && words[0].isEmpty()) continue;
            int parts = (int) Math.ceil(words.length / (double) MAX_WORDS);
            int per = (int) Math.ceil(words.length / (double) parts);
            for (int i = 0; i < words.length; i += per) {
                out.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, Math.min(words.length, i + per))));
            }
        }
        return out;
    }

    static String ts(double seconds) {
        long ms = Math.round(seconds * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
