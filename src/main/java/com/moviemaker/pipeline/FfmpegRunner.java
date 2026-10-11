package com.moviemaker.pipeline;

import com.moviemaker.config.AppProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class FfmpegRunner {
    private final AppProperties props;

    public FfmpegRunner(AppProperties props) {
        this.props = props;
    }

    public void ffmpeg(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(props.ffmpeg(), "-y", "-nostdin", "-hide_banner", "-loglevel", "error"));
        cmd.addAll(List.of(args));
        run(cmd, cwd, 30);
    }

    /** Writes raw frames to ffmpeg's stdin (the command must read {@code -i pipe:0}) while it encodes. */
    public void ffmpegWithStdin(Path cwd, StdinWriter writer, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(props.ffmpeg(), "-y", "-hide_banner", "-loglevel", "error"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < 8000) out.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        drain.setDaemon(true);
        drain.start();
        try (java.io.OutputStream os = new java.io.BufferedOutputStream(p.getOutputStream(), 1 << 20)) {
            writer.write(os);
        } catch (IOException e) {
            // ffmpeg closed its input early (e.g. it hit -t); its exit code decides success below
            if (p.isAlive() && !p.waitFor(1, TimeUnit.SECONDS)) throw e;
        }
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg timed out");
        }
        drain.join(2000);
        if (p.exitValue() != 0) throw new IOException("ffmpeg failed (" + p.exitValue() + "): " + out);
    }

    @FunctionalInterface
    public interface StdinWriter {
        void write(java.io.OutputStream os) throws IOException;
    }

    public double durationSeconds(Path file) throws IOException, InterruptedException {
        String out = run(List.of(props.ffprobe(), "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                file.toAbsolutePath().toString()), file.getParent(), 2);
        return Double.parseDouble(out.strip());
    }

    private String run(List<String> cmd, Path cwd, int timeoutMinutes) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() < 8000) out.append(line).append('\n');
            }
        }
        if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg timed out: " + cmd.get(0));
        }
        if (p.exitValue() != 0) {
            throw new IOException("ffmpeg failed (" + p.exitValue() + "): " + out);
        }
        return out.toString();
    }
}
