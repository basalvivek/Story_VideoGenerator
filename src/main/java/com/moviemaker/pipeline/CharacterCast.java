package com.moviemaker.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out who is in a scene and how they are staged, from the narration (English and Hindi):
 * which characters, how many of each ("four sons"), and, when someone is speaking to others, who speaks and who listens.
 */
public final class CharacterCast {
    private CharacterCast() {}

    public enum Kind {
        KING, QUEEN, PRINCE, PRINCESS, BOY, GIRL, MAN, WOMAN, FATHER, MOTHER, ELDER, FARMER, SOLDIER, MERCHANT,
        DOG, CAT, HORSE, LION, RABBIT, ELEPHANT, FOX, COW, MONKEY, DEER, BEAR, BIRD, FISH;

        public boolean human() { return ordinal() < DOG.ordinal(); }
    }

    /** One person or animal on screen. */
    public record Member(Kind kind, boolean speaker) {}

    /** @param conversation true when one character addresses the others (speaker + listeners) */
    public record Staging(List<Member> members, boolean conversation) {}

    private static final int MAX_HUMANS = 7, MAX_ANIMALS = 2;

    private record Rule(Kind kind, Pattern en, String[] hi, String[] hiPlural) {}

    private static Rule rule(Kind k, String en, String[] hi, String... hiPlural) {
        return new Rule(k, Pattern.compile("\\b(?:" + en + ")\\b", Pattern.CASE_INSENSITIVE), hi, hiPlural);
    }

    private static String[] hi(String... s) { return s; }

    private static final List<Rule> RULES = List.of(
            rule(Kind.KING, "kings?|emperors?|rajas?|sultans?", hi("राजा", "सम्राट", "बादशाह")),
            rule(Kind.QUEEN, "queens?|empress|ranis?", hi("रानी", "महारानी")),
            rule(Kind.PRINCESS, "princess(?:es)?", hi("राजकुमारी")),
            rule(Kind.PRINCE, "princes?", hi("राजकुमार")),
            rule(Kind.FATHER, "fathers?|dad|daddy|papa", hi("पिता", "पापा", "बाप", "पिताजी")),
            rule(Kind.MOTHER, "mothers?|mom|mommy|mama", hi("माता", "मम्मी", "माँ")),
            rule(Kind.BOY, "boys?|kids?|child|children|sons?|lads?|students?|brothers?",
                    hi("लड़का", "बालक", "बेटा", "बच्चा", "छात्र"), "लड़के", "बेटे", "बेटों", "बच्चे", "बच्चों", "छात्रों", "पुत्रों", "पुत्र", "भाइयों", "भाई"),
            rule(Kind.GIRL, "girls?|daughters?|maiden|sisters?", hi("लड़की", "बालिका", "बेटी", "कन्या", "बहन"), "लड़कियाँ", "बेटियाँ", "बेटियों", "बहनें"),
            rule(Kind.ELDER, "old (?:man|woman)|elders?|grandfather|grandmother|grandpa|grandma|sage|hermit|saint",
                    hi("बूढ़ा", "बुजुर्ग", "बूढ़ी", "दादा", "दादी", "नाना", "नानी", "साधु", "ऋषि", "संत")),
            rule(Kind.FARMER, "farmers?|villagers?|shepherds?|peasants?|woodcutters?", hi("किसान", "गाँव वाले", "गड़रिया", "लकड़हारा")),
            rule(Kind.SOLDIER, "soldiers?|warriors?|guards?|knights?|army|commander", hi("सैनिक", "योद्धा", "सिपाही", "पहरेदार", "सेनापति")),
            rule(Kind.MERCHANT, "merchants?|traders?|shopkeepers?|vendors?|minister|wazir", hi("व्यापारी", "सौदागर", "दुकानदार", "मंत्री", "वज़ीर")),
            rule(Kind.WOMAN, "woman|women|lady|wife|she|her", hi("औरत", "महिला", "पत्नी", "स्त्री")),
            rule(Kind.MAN, "man|men|husband|stranger|traveller|traveler|he|his", hi("आदमी", "पुरुष", "पति", "यात्री")),
            rule(Kind.DOG, "dogs?|puppy|puppies|wolf|wolves|jackals?", hi("कुत्ता", "कुत्ते", "भेड़िया", "सियार")),
            rule(Kind.CAT, "cats?|kittens?|tigers?|leopards?|panthers?", hi("बिल्ली", "बाघ", "तेंदुआ")),
            rule(Kind.HORSE, "horses?|ponies|pony|donkeys?|mules?", hi("घोड़ा", "घोड़े", "गधा")),
            rule(Kind.LION, "lions?", hi("शेर", "सिंह")),
            rule(Kind.RABBIT, "rabbits?|hares?|mice|mouse|rats?|squirrels?", hi("खरगोश", "चूहा", "गिलहरी")),
            rule(Kind.ELEPHANT, "elephants?", hi("हाथी")),
            rule(Kind.FOX, "foxe?s?", hi("लोमड़ी")),
            rule(Kind.COW, "cows?|bulls?|oxen|buffalos?|buffaloes|goats?|sheep|calf", hi("गाय", "बैल", "भैंस", "बकरी")),
            rule(Kind.MONKEY, "monkeys?|apes?|langurs?", hi("बंदर", "बन्दर", "लंगूर")),
            rule(Kind.DEER, "deer|stags?|antelopes?", hi("हिरण", "हिरन")),
            rule(Kind.BEAR, "bears?", hi("भालू")),
            rule(Kind.BIRD, "birds?|crows?|sparrows?|parrots?|eagles?|owls?|pigeons?|doves?|peacocks?|swans?|ducks?|cranes?",
                    hi("पक्षी", "चिड़िया", "कौआ", "तोता", "मोर", "कबूतर", "उल्लू", "चील")),
            rule(Kind.FISH, "fish|fishes|dolphins?|whales?|sharks?", hi("मछली", "डॉल्फ़िन")));

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("both", 2),
            Map.entry("एक", 1), Map.entry("दो", 2), Map.entry("तीन", 3), Map.entry("चार", 4), Map.entry("पाँच", 5), Map.entry("पांच", 5),
            Map.entry("छह", 6), Map.entry("छः", 6), Map.entry("सात", 7),
            Map.entry("1", 1), Map.entry("2", 2), Map.entry("3", 3), Map.entry("4", 4), Map.entry("5", 5), Map.entry("6", 6), Map.entry("7", 7));

    private static final Pattern SPEECH_EN = Pattern.compile(
            "\\b(?:said|says|told|tells|asked|asks|spoke|speaks|speaking|replied|answered|called|shouted|whispered|explained|advised|"
                    + "taught|teaches|warned|announced|declared|ordered|commanded|cried|talked|talking|gathered|addressed|instructed|counselled|counseled)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String[] SPEECH_HI = {"कहा", "बोला", "बोली", "बताया", "समझाया", "पूछा", "सुनाया", "बुलाया", "आदेश", "चिल्लाया", "सिखाया", "समझाते", "कहते", "बोले"};

    private record Hit(Kind kind, int pos, int count) {}

    /** Distinct characters in order of first mention (compat helper). */
    public static List<Kind> detect(String narration, int max) {
        List<Kind> out = new ArrayList<>();
        for (Member m : stage(narration).members()) if (!out.contains(m.kind())) out.add(m.kind());
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    public static Staging stage(String narration) {
        String text = narration == null ? "" : narration;
        List<Hit> hits = new ArrayList<>();
        for (Rule r : RULES) {
            int pos = Integer.MAX_VALUE, count = 1;
            Matcher m = r.en().matcher(text);
            if (m.find()) {
                pos = m.start();
                count = countBefore(text, m.start(), m.group());
            }
            for (String w : r.hi()) {
                int i = text.indexOf(w);
                if (i >= 0 && i < pos) { pos = i; count = hindiCount(text, i, false); }
            }
            for (String w : r.hiPlural()) {
                int i = text.indexOf(w);
                if (i >= 0 && i < pos) { pos = i; count = hindiCount(text, i, true); }
            }
            if (pos != Integer.MAX_VALUE) hits.add(new Hit(r.kind(), pos, count));
        }
        hits.sort((a, b) -> Integer.compare(a.pos(), b.pos()));

        // a specific role (father, farmer, king...) replaces the generic man/woman that pronouns would add
        boolean specificHuman = hits.stream().anyMatch(h -> h.kind().human() && h.kind() != Kind.MAN && h.kind() != Kind.WOMAN);
        if (specificHuman) hits.removeIf(h -> h.kind() == Kind.MAN || h.kind() == Kind.WOMAN);
        if (hits.isEmpty()) {
            hits.add(new Hit(text.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:she|her)\\b.*") ? Kind.WOMAN : Kind.MAN, 0, 1));
        }

        // pick the speaker: the character who addresses the others
        int verb = speechVerbPos(text);
        Kind speakerKind = null;
        if (verb >= 0) {
            boolean hindi = text.matches("(?s).*[ऀ-ॿ].*");
            Hit best = null;
            for (Hit h : hits) {
                if (!h.kind().human() || h.pos() >= verb) continue;
                if (best == null || (!hindi && h.pos() > best.pos())) best = h;   // English: subject sits right before the verb; Hindi: first mention
            }
            if (best == null) best = hits.stream().filter(h -> h.kind().human()).findFirst().orElse(null);
            if (best != null) speakerKind = best.kind();
        }

        List<Member> humans = new ArrayList<>(), animals = new ArrayList<>();
        for (Hit h : hits) {
            List<Member> bucket = h.kind().human() ? humans : animals;
            int cap = h.kind().human() ? MAX_HUMANS : MAX_ANIMALS;
            int n = h.kind().human() ? h.count() : Math.min(h.count(), 2);
            for (int i = 0; i < n && bucket.size() < cap; i++) {
                bucket.add(new Member(h.kind(), h.kind() == speakerKind && i == 0));
            }
        }
        boolean conversation = speakerKind != null && humans.size() >= 2;
        if (conversation && humans.stream().noneMatch(Member::speaker)) conversation = false;
        List<Member> all = new ArrayList<>(humans);
        all.addAll(animals);
        return new Staging(all, conversation);
    }

    private static int speechVerbPos(String text) {
        int best = Integer.MAX_VALUE;
        Matcher m = SPEECH_EN.matcher(text);
        if (m.find()) best = m.start();
        for (String v : SPEECH_HI) {
            int i = text.indexOf(v);
            if (i >= 0 && i < best) best = i;
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /** "four sons" -> 4; plural without a number -> 3; otherwise 1. */
    private static int countBefore(String text, int pos, String matched) {
        String before = text.substring(Math.max(0, pos - 24), pos).toLowerCase(Locale.ROOT).strip();
        String[] words = before.split("[^\\p{L}\\p{N}]+");
        for (int k = words.length - 1; k >= Math.max(0, words.length - 3); k--) {
            Integer n = NUMBERS.get(words[k]);
            if (n != null && !words[k].equals("a") && !words[k].equals("an")) return n;
        }
        String w = matched.toLowerCase(Locale.ROOT);
        boolean plural = (w.endsWith("s") && !w.endsWith("ss") && !w.equals("his") && !w.equals("princess"))
                || w.equals("children") || w.equals("men") || w.equals("women") || w.equals("wolves") || w.equals("mice")
                || w.equals("oxen") || w.equals("princesses");
        return plural ? 3 : 1;
    }

    private static int hindiCount(String text, int pos, boolean plural) {
        String before = text.substring(Math.max(0, pos - 12), pos).strip();
        String[] words = before.split("[^\\p{L}\\p{N}\\p{M}]+");
        for (int k = words.length - 1; k >= Math.max(0, words.length - 2); k--) {
            Integer n = NUMBERS.get(words[k]);
            if (n != null) return n;
        }
        return plural ? 3 : 1;
    }
}
