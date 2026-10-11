package com.moviemaker.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Voices installed on this machine (Windows SAPI). Empty on platforms without SAPI; narration then degrades to silence. */
@Component
public class VoiceCatalog {
    private static final Logger log = LoggerFactory.getLogger(VoiceCatalog.class);

    public record Voice(String name, String culture, String gender) {}

    public record Language(String code, String label, boolean native_) {}

    private static final Map<String, String> LANG_NAMES = Map.ofEntries(
            Map.entry("en", "English"), Map.entry("de", "German"), Map.entry("es", "Spanish"), Map.entry("fr", "French"),
            Map.entry("it", "Italian"), Map.entry("ja", "Japanese"), Map.entry("ko", "Korean"), Map.entry("ru", "Russian"),
            Map.entry("zh", "Chinese"), Map.entry("pt", "Portuguese"), Map.entry("hi", "Hindi"), Map.entry("nl", "Dutch"));

    private final List<Voice> voices = new ArrayList<>();

    public VoiceCatalog() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Speech; $s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                            + "$s.GetInstalledVoices() | Where-Object {$_.Enabled} | ForEach-Object { $_.VoiceInfo.Name + '|' + $_.VoiceInfo.Culture.Name + '|' + $_.VoiceInfo.Gender }")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.strip().split("\\|");
                    if (parts.length == 3) voices.add(new Voice(parts[0], parts[1], parts[2].toLowerCase(Locale.ROOT)));
                }
            }
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            log.warn("Could not enumerate SAPI voices: {}", e.toString());
        }
        if (edgeTtsInstalled()) {
            voices.add(new Voice(EDGE_PREFIX + "hi-IN-SwaraNeural", "hi-IN", "female"));
            voices.add(new Voice(EDGE_PREFIX + "hi-IN-MadhurNeural", "hi-IN", "male"));
        }
        log.info("Installed voices: {}", voices);
    }

    /** Voice-name prefix marking online Microsoft Edge neural voices (python edge-tts); no SAPI voice exists for Hindi. */
    public static final String EDGE_PREFIX = "edge:";

    private static boolean edgeTtsInstalled() {
        try {
            Process p = new ProcessBuilder("python", "-c", "import edge_tts").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.warn("python edge-tts not available (Hindi narration disabled): {}", e.toString());
            return false;
        }
    }

    public boolean available() { return !voices.isEmpty(); }

    public List<Voice> voices() { return List.copyOf(voices); }

    /** Languages offered in the UI: every installed culture, plus English (UK) mapped to the closest English voice. */
    public List<Language> languages() {
        Map<String, Language> out = new LinkedHashMap<>();
        boolean anyEnglish = false;
        for (Voice v : voices) {
            if (v.culture().startsWith("en")) anyEnglish = true;
            out.putIfAbsent(v.culture(), new Language(v.culture(), label(v.culture()), true));
        }
        if (anyEnglish) out.putIfAbsent("en-GB", new Language("en-GB", "English (UK)", false));
        // Always offered; needs the Windows Hindi voice pack installed to actually speak Hindi.
        if (out.keySet().stream().noneMatch(c -> c.startsWith("hi"))) out.put("hi-IN", new Language("hi-IN", "Hindi", false));
        if (out.isEmpty()) out.put("en-GB", new Language("en-GB", "English (UK)", false));
        List<Language> list = new ArrayList<>(out.values());
        list.sort((a, b) -> a.code().startsWith("en") == b.code().startsWith("en")
                ? a.label().compareTo(b.label()) : (a.code().startsWith("en") ? -1 : 1));
        return list;
    }

    public Optional<Voice> pick(String culture, String gender) {
        if (voices.isEmpty()) return Optional.empty();
        String lang = culture.length() >= 2 ? culture.substring(0, 2) : culture;
        return voices.stream().filter(v -> v.culture().equalsIgnoreCase(culture) && v.gender().equals(gender)).findFirst()
                .or(() -> voices.stream().filter(v -> v.culture().startsWith(lang) && v.gender().equals(gender)).findFirst())
                .or(() -> voices.stream().filter(v -> v.culture().equalsIgnoreCase(culture)).findFirst())
                .or(() -> voices.stream().filter(v -> v.culture().startsWith(lang)).findFirst())
                .or(() -> voices.stream().filter(v -> v.culture().startsWith("en") && v.gender().equals(gender)).findFirst())
                .or(() -> Optional.of(voices.get(0)));
    }

    private static String label(String culture) {
        String lang = culture.substring(0, 2);
        String base = LANG_NAMES.getOrDefault(lang, new Locale(lang).getDisplayLanguage(Locale.ENGLISH));
        String region = culture.length() > 3 ? culture.substring(3) : "";
        return switch (region) {
            case "US" -> base + " (US)";
            case "GB" -> base + " (UK)";
            case "IN" -> lang.equals("hi") ? base : base + " (IN)";
            case "" -> base;
            default -> base + " (" + region + ")";
        };
    }
}
