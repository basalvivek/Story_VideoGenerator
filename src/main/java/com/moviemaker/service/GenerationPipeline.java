package com.moviemaker.service;

import com.moviemaker.config.AppProperties;
import com.moviemaker.domain.MediaFile;
import com.moviemaker.domain.ProjectStatus;
import com.moviemaker.domain.VideoProject;
import com.moviemaker.domain.VideoScene;
import com.moviemaker.pipeline.AiSceneArtist;
import com.moviemaker.pipeline.ImageRenderer;
import com.moviemaker.pipeline.StoryPlanner;
import com.moviemaker.pipeline.StoryPlanner.ScenePlan;
import com.moviemaker.pipeline.TextExtractor;
import com.moviemaker.pipeline.TtsService;
import com.moviemaker.pipeline.VideoAssembler;
import com.moviemaker.pipeline.VideoAssembler.SceneInput;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** story file -> text -> scenes -> artwork -> narration -> encoded video, all persisted in PostgreSQL. */
@Service
public class GenerationPipeline {
    private static final Logger log = LoggerFactory.getLogger(GenerationPipeline.class);

    public record ProjectCreated(UUID projectId) {}

    private final AppProperties props;
    private final ProjectStore store;
    private final ProgressService progress;
    private final TextExtractor extractor;
    private final StoryPlanner planner;
    private final ImageRenderer renderer;
    private final TtsService tts;
    private final VideoAssembler assembler;
    private final AiSceneArtist artist;

    public GenerationPipeline(AppProperties props, ProjectStore store, ProgressService progress, TextExtractor extractor,
                              StoryPlanner planner, ImageRenderer renderer, TtsService tts, VideoAssembler assembler,
                              AiSceneArtist artist) {
        this.artist = artist;
        this.props = props;
        this.store = store;
        this.progress = progress;
        this.extractor = extractor;
        this.planner = planner;
        this.renderer = renderer;
        this.tts = tts;
        this.assembler = assembler;
    }

    @Async("generationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(ProjectCreated e) {
        run(e.projectId());
    }

    public void run(UUID id) {
        Path dir = null;
        try {
            VideoProject p = store.project(id);
            dir = Files.createDirectories(Path.of(props.workDir()).resolve(id.toString()));

            progress.update(id, ProjectStatus.EXTRACTING, 5, "Reading your document");
            byte[] src = store.fileBytes(p.getSourceFileId());
            TextExtractor.Format fmt = TextExtractor.detect(src, p.getSourceFilename());
            if (fmt == null) throw new IllegalStateException("Unsupported document type");
            String text = extractor.extract(src, fmt);
            if (text == null || text.strip().length() < 20) {
                throw new IllegalStateException("No readable text found in the document (scanned PDFs are not supported)");
            }

            progress.update(id, ProjectStatus.PLANNING, 12, "Splitting the story into scenes");
            List<ScenePlan> plan = planner.plan(text, p.getLengthMinutes());
            if (plan.isEmpty()) throw new IllegalStateException("Could not build any scenes from the document");

            progress.update(id, ProjectStatus.RENDERING, 18, "Creating scene artwork");
            List<Path> images = new ArrayList<>();
            List<byte[]> thumbs = new ArrayList<>();
            List<Boolean> aiPainted = new ArrayList<>();
            AiSceneArtist.Direction direction = null;
            if (artist.enabled()) {
                progress.update(id, ProjectStatus.RENDERING, 18, "Planning realistic scenes and characters");
                direction = artist.direct(plan, p.getTitle());
            }
            for (ScenePlan s : plan) {
                BufferedImage img = null;
                if (direction != null) {
                    progress.update(id, ProjectStatus.RENDERING, 18 + s.index() * 12 / plan.size(),
                            "Painting realistic scene " + (s.index() + 1) + "/" + plan.size());
                    img = artist.paint(direction, s, p.getStyle());
                }
                aiPainted.add(img != null);
                if (img == null) img = renderer.render(p.getStyle(), p.getTitle(), s.title(), s.narration(), s.index());
                Path f = dir.resolve("scene_" + s.index() + ".png");
                ImageIO.write(img, "png", f.toFile());
                images.add(f);
                thumbs.add(ImageRenderer.jpeg(img, 320, 0.8f));
                progress.update(id, ProjectStatus.RENDERING, 18 + (s.index() + 1) * 12 / plan.size(), "Creating scene artwork");
            }

            List<SceneInput> inputs = new ArrayList<>();
            boolean narrate = p.isNarration() && tts.available();
            for (ScenePlan s : plan) {
                progress.update(id, ProjectStatus.NARRATING, 30 + s.index() * 25 / plan.size(),
                        narrate ? "Recording narration " + (s.index() + 1) + "/" + plan.size() : "Timing scenes");
                Path wav = null;
                double speech;
                if (narrate) {
                    Optional<Path> w = tts.synthesize(s.narration(), p.getVoiceLanguage(), p.getVoiceType(), dir, "voice_" + s.index());
                    wav = w.orElse(null);
                    speech = wav != null ? TtsService.wavSeconds(wav) : estimate(s.narration());
                } else {
                    speech = estimate(s.narration());
                }
                double duration = Math.max(5, VideoAssembler.LEAD_IN + speech + VideoAssembler.TAIL);
                inputs.add(new SceneInput(s.index(), s.narration(), images.get(s.index()), wav, duration, speech, !aiPainted.get(s.index())));
            }

            progress.update(id, ProjectStatus.ASSEMBLING, 58, "Encoding video");
            Path out = assembler.assemble(dir, inputs, p.isSubtitles(), p.isMusic(), p.getStyle(),
                    pct -> progress.update(id, ProjectStatus.ASSEMBLING, 58 + pct * 40 / 100, "Encoding video"));

            double total = inputs.stream().mapToDouble(SceneInput::duration).sum();
            store.saveResult(id, plan, inputs, thumbs, Files.readAllBytes(out), p.getTitle());
            log.info("Project {} completed: {} scenes, {}s", id, plan.size(), Math.round(total));
        } catch (Exception ex) {
            log.error("Project {} failed", id, ex);
            progress.fail(id, rootMessage(ex));
        } finally {
            if (dir != null) deleteQuietly(dir);
        }
    }

    private static double estimate(String narration) {
        return narration.strip().split("\\s+").length / StoryPlanner.WORDS_PER_MINUTE * 60.0;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = t.getMessage() != null ? t.getMessage() : r.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 1500 ? m.substring(0, 1500) : m;
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(pth -> pth.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Transactional DB access kept in its own bean so the long pipeline never holds a transaction open. */
    @Service
    public static class ProjectStore {
        private final com.moviemaker.repo.VideoProjectRepository projects;
        private final com.moviemaker.repo.MediaFileRepository files;
        private final com.moviemaker.repo.VideoSceneRepository scenes;
        private final ProgressService progress;

        public ProjectStore(com.moviemaker.repo.VideoProjectRepository projects, com.moviemaker.repo.MediaFileRepository files,
                            com.moviemaker.repo.VideoSceneRepository scenes, ProgressService progress) {
            this.projects = projects;
            this.files = files;
            this.scenes = scenes;
            this.progress = progress;
        }

        @Transactional(readOnly = true)
        public VideoProject project(UUID id) {
            return projects.findById(id).orElseThrow();
        }

        @Transactional(readOnly = true)
        public byte[] fileBytes(UUID id) {
            return files.findById(id).orElseThrow().getData();
        }

        @Transactional
        public void saveResult(UUID projectId, List<ScenePlan> plan, List<SceneInput> inputs, List<byte[]> thumbs,
                               byte[] video, String title) {
            scenes.deleteByProjectId(projectId);
            double start = 0;
            for (ScenePlan s : plan) {
                SceneInput in = inputs.get(s.index());
                MediaFile thumb = files.save(new MediaFile("scene_" + (s.index() + 1) + ".jpg", "image/jpeg", thumbs.get(s.index())));
                scenes.save(new VideoScene(projectId, s.index(), s.title(), s.narration(), start, in.duration(), thumb.getId()));
                start += in.duration();
            }
            String safe = title.replaceAll("[^A-Za-z0-9 _-]", "").strip().replace(' ', '_');
            MediaFile v = files.save(new MediaFile((safe.isEmpty() ? "video" : safe) + ".mp4", "video/mp4", video));
            final double total = start;
            projects.findById(projectId).ifPresent(p -> p.complete(v.getId(), total));
        }
    }
}
