package com.moviemaker.pipeline;

import com.moviemaker.domain.VideoStyle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import org.springframework.stereotype.Component;

/** Encodes scenes (Ken Burns image + narration + burned-in captions), joins them and mixes the background music. */
@Component
public class VideoAssembler {
    public static final double LEAD_IN = 0.5, TAIL = 0.9;
    private static final int FPS = 25;

    /** @param cartoons overlay animated cartoon characters; false when the artwork already contains the people (AI images) */
    public record SceneInput(int index, String narration, Path image, Path wav, double duration, double speechSeconds, boolean cartoons) {}

    private final FfmpegRunner ffmpeg;

    public VideoAssembler(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public Path assemble(Path dir, List<SceneInput> scenes, boolean subtitles, boolean music, VideoStyle style,
                         IntConsumer percent) throws IOException, InterruptedException {
        List<String> parts = new ArrayList<>();
        boolean anyNarration = false;
        for (int i = 0; i < scenes.size(); i++) {
            SceneInput s = scenes.get(i);
            encodeScene(dir, s, subtitles, i);
            parts.add("file 'scene_" + s.index() + ".mp4'");
            anyNarration |= s.wav() != null;
            percent.accept((i + 1) * 100 / (scenes.size() + 1));
        }
        Files.writeString(dir.resolve("list.txt"), String.join("\n", parts) + "\n", StandardCharsets.UTF_8);
        ffmpeg.ffmpeg(dir, "-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", "joined.mp4");
        double total = ffmpeg.durationSeconds(dir.resolve("joined.mp4"));

        if (!music) {
            ffmpeg.ffmpeg(dir, "-i", "joined.mp4", "-c", "copy", "-movflags", "+faststart", "final.mp4");
        } else {
            writeMusicCycle(dir, style);
            double vol = anyNarration ? 0.16 : 0.45;
            String f = String.format(Locale.ROOT,
                    "[1:a]volume=%.2f,afade=t=in:d=2,afade=t=out:st=%.2f:d=3[m];"
                            + "[0:a][m]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]",
                    vol, Math.max(0, total - 3));
            ffmpeg.ffmpeg(dir, "-i", "joined.mp4", "-stream_loop", "-1", "-i", "music.wav", "-filter_complex", f,
                    "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-shortest",
                    "-movflags", "+faststart", "final.mp4");
        }
        percent.accept(100);
        return dir.resolve("final.mp4");
    }

    private void encodeScene(Path dir, SceneInput s, boolean subtitles, int ordinal) throws IOException, InterruptedException {
        double d = s.duration();
        int frames = (int) Math.ceil(d * FPS);
        String zoom = switch (ordinal % 3) {
            case 0 -> "z='min(zoom+" + String.format(Locale.ROOT, "%.6f", 0.18 / frames) + ",1.18)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'";
            case 1 -> "z='if(eq(on,0),1.18,max(zoom-" + String.format(Locale.ROOT, "%.6f", 0.18 / frames) + ",1.0))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'";
            default -> "z='1.12':x='(iw-iw/zoom)*on/" + frames + "':y='ih/2-(ih/zoom/2)'";
        };
        StringBuilder vf = new StringBuilder("[0:v]scale=1920:1080,zoompan=" + zoom + ":d=" + frames + ":s=1280x720:fps=" + FPS);
        // animated characters (from the narration) arrive as a transparent raw-video overlay on stdin
        CharacterAnimator animator = s.cartoons()
                ? new CharacterAnimator(CharacterCast.stage(s.narration()), s.index()) : null;
        if (animator != null) vf.append("[bg];[bg][2:v]overlay=format=auto:eof_action=pass");
        vf.append(String.format(Locale.ROOT, ",fade=t=in:st=0:d=0.5,fade=t=out:st=%.2f:d=0.5", Math.max(0, d - 0.5)));
        if (subtitles && s.narration() != null && !s.narration().isBlank()) {
            String srt = SubtitleBuilder.srt(SubtitleBuilder.cues(s.narration(), LEAD_IN, s.speechSeconds()));
            Files.writeString(dir.resolve("sub_" + s.index() + ".srt"), srt, StandardCharsets.UTF_8);
            vf.append(",subtitles=sub_").append(s.index())
              .append(".srt:force_style='FontName=").append(s.narration().matches("(?s).*[ऀ-ॿ].*") ? "Nirmala UI" : "Segoe UI").append(",FontSize=15,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H99000000,BackColour=&H66000000,BorderStyle=1,Outline=1.6,Shadow=0.6,MarginV=26,Alignment=2'");
        }
        vf.append("[v]");

        List<String> args = new ArrayList<>(List.of("-i", s.image().getFileName().toString()));
        String af;
        if (s.wav() != null) {
            args.addAll(List.of("-i", s.wav().getFileName().toString()));
            af = String.format(Locale.ROOT, "[1:a]aresample=44100,aformat=channel_layouts=stereo,adelay=%d|%d,apad=whole_dur=%.3f[a]",
                    (int) (LEAD_IN * 1000), (int) (LEAD_IN * 1000), d);
        } else {
            args.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo"));
            af = "[1:a]anull[a]";
        }
        if (animator != null) {
            args.addAll(List.of("-f", "rawvideo", "-pix_fmt", "bgra", "-s", CharacterAnimator.W + "x" + CharacterAnimator.H,
                    "-framerate", String.valueOf(FPS), "-i", "pipe:0"));
        }
        args.addAll(List.of("-filter_complex", vf + ";" + af, "-map", "[v]", "-map", "[a]",
                "-t", String.format(Locale.ROOT, "%.3f", d),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p", "-r", String.valueOf(FPS),
                "-c:a", "aac", "-b:a", "160k", "-ar", "44100", "-ac", "2", "scene_" + s.index() + ".mp4"));
        if (animator != null) {
            ffmpeg.ffmpegWithStdin(dir, os -> writeCharacterFrames(os, animator, frames), args.toArray(String[]::new));
        } else {
            ffmpeg.ffmpeg(dir, args.toArray(String[]::new));
        }
    }

    private static void writeCharacterFrames(java.io.OutputStream os, CharacterAnimator animator, int frames) throws IOException {
        BufferedImage frame = new BufferedImage(CharacterAnimator.W, CharacterAnimator.H, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        ByteBuffer bytes = ByteBuffer.allocate(px.length * 4).order(ByteOrder.LITTLE_ENDIAN);   // BGRA byte order
        for (int i = 0; i < frames; i++) {
            Arrays.fill(px, 0);
            animator.draw(frame, i / (double) FPS);
            bytes.clear();
            bytes.asIntBuffer().put(px);
            os.write(bytes.array(), 0, bytes.capacity());
        }
    }

    // ---- background music: slow synthesized pads, one chord every few seconds ---------------------------------------

    private static final double[][] CINEMATIC = {{220, 261.63, 329.63}, {174.61, 220, 261.63}, {130.81 * 2, 164.81 * 2, 196 * 2}, {196, 246.94, 293.66}};
    private static final double[][] ANIMATED = {{261.63, 329.63, 392}, {196, 246.94, 293.66}, {220, 261.63, 329.63}, {174.61, 220, 261.63}};
    private static final double[][] DOCUMENTARY = {{146.83 * 2, 174.61 * 2, 220 * 1.0}, {233.08, 293.66, 349.23}, {174.61, 220, 261.63}, {130.81 * 2, 164.81 * 2, 196 * 2}};
    private static final double[][] MODERN = {{164.81, 196, 246.94}, {130.81 * 2, 164.81 * 2, 196 * 2}, {196, 246.94, 293.66}, {146.83 * 2, 185 * 1.0, 220}};

    private void writeMusicCycle(Path dir, VideoStyle style) throws IOException, InterruptedException {
        double[][] chords = switch (style) {
            case CINEMATIC -> CINEMATIC;
            case ANIMATED -> ANIMATED;
            case DOCUMENTARY -> DOCUMENTARY;
            case MODERN -> MODERN;
        };
        double len = style == VideoStyle.ANIMATED ? 5 : style == VideoStyle.MODERN ? 6 : 8;
        List<String> args = new ArrayList<>();
        StringBuilder fg = new StringBuilder();
        for (int i = 0; i < chords.length; i++) {
            StringBuilder e = new StringBuilder("0.16*(");
            for (double f : chords[i]) e.append(String.format(Locale.ROOT, "sin(2*PI*%.2f*t)+0.5*sin(2*PI*%.2f*t)+", f, f * 2.003));
            e.append(String.format(Locale.ROOT, "0.8*sin(2*PI*%.2f*t))", chords[i][0] / 2));
            e.append(String.format(Locale.ROOT, "*(0.75+0.25*sin(2*PI*0.23*t))*pow(sin(PI*t/%.1f),0.6)", len));
            fg.append(String.format(Locale.ROOT, "aevalsrc='%s':d=%.1f:s=44100:c=stereo[c%d];", e, len, i));
        }
        for (int i = 0; i < chords.length; i++) fg.append("[c").append(i).append(']');
        fg.append("concat=n=").append(chords.length).append(":v=0:a=1,lowpass=f=2400,aecho=0.8:0.6:380|700:0.35|0.25[out]");
        args.addAll(List.of("-filter_complex", fg.toString(), "-map", "[out]", "music.wav"));
        ffmpeg.ffmpeg(dir, args.toArray(String[]::new));
    }
}
