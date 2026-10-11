package com.moviemaker.pipeline;

import com.moviemaker.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.springframework.stereotype.Component;

/** Text-to-speech through the installed Windows SAPI voices. */
@Component
public class TtsService {
    private final VoiceCatalog catalog;
    private Path script;

    private final AppProperties props;

    public TtsService(VoiceCatalog catalog, AppProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    public boolean available() { return catalog.available(); }

    /** @return path of the produced WAV, or empty if no voice could speak the text. */
    public Optional<Path> synthesize(String text, String language, String gender, Path workDir, String baseName)
            throws IOException, InterruptedException {
        Optional<VoiceCatalog.Voice> voice = catalog.pick(language, gender);
        if (voice.isEmpty()) return Optional.empty();
        Path txt = workDir.resolve(baseName + ".txt");
        Path wav = workDir.resolve(baseName + ".wav");
        Files.writeString(txt, text, StandardCharsets.UTF_8);
        if (voice.get().name().startsWith(VoiceCatalog.EDGE_PREFIX)) return Optional.of(edge(voice.get(), txt, wav, workDir, baseName));
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script().toString(), "-TextFile", txt.toString(), "-OutWav", wav.toString(),
                "-Voice", voice.get().name(), "-Rate", "0")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(3, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("TTS timed out");
        }
        if (p.exitValue() != 0 || !Files.exists(wav)) throw new IOException("TTS failed: " + out);
        return Optional.of(wav);
    }

    /** Online neural voice via python edge-tts (MP3), converted to the 44.1 kHz WAV the assembler expects. */
    private Path edge(VoiceCatalog.Voice voice, Path txt, Path wav, Path workDir, String baseName)
            throws IOException, InterruptedException {
        Path mp3 = workDir.resolve(baseName + ".mp3");
        run(new ProcessBuilder("python", "-m", "edge_tts", "--voice", voice.name().substring(VoiceCatalog.EDGE_PREFIX.length()),
                "--file", txt.toString(), "--write-media", mp3.toString()), 3);
        run(new ProcessBuilder(props.ffmpeg(), "-y", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", mp3.toString(), "-ar", "44100", "-ac", "1", wav.toString()), 2);
        if (!Files.exists(wav)) throw new IOException("TTS failed: no audio produced");
        return wav;
    }

    private static void run(ProcessBuilder pb, int minutes) throws IOException, InterruptedException {
        Process p = pb.redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("TTS timed out");
        }
        if (p.exitValue() != 0) throw new IOException("TTS failed: " + out);
    }

    public static double wavSeconds(Path wav) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat f = in.getFormat();
            return in.getFrameLength() / f.getFrameRate();
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unreadable WAV: " + wav, e);
        }
    }

    private synchronized Path script() throws IOException {
        if (script == null) {
            Path dir = Files.createTempDirectory("stv-tts");
            script = dir.resolve("speak.ps1");
            try (InputStream in = getClass().getResourceAsStream("/speak.ps1")) {
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            script.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
        }
        return script;
    }
}
