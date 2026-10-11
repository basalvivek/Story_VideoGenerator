package com.moviemaker.service;

import com.moviemaker.config.AppProperties;
import com.moviemaker.domain.MediaFile;
import com.moviemaker.domain.ProjectStatus;
import com.moviemaker.domain.VideoProject;
import com.moviemaker.domain.VideoScene;
import com.moviemaker.domain.VideoStyle;
import com.moviemaker.pipeline.TextExtractor;
import com.moviemaker.pipeline.VoiceCatalog;
import com.moviemaker.repo.MediaFileRepository;
import com.moviemaker.repo.VideoProjectRepository;
import com.moviemaker.repo.VideoSceneRepository;
import com.moviemaker.web.ApiException;
import com.moviemaker.web.Dtos.ProjectDto;
import com.moviemaker.web.Dtos.SceneDto;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final Set<Integer> LENGTHS = Set.of(1, 3, 5, 10, 15, 30);

    private final VideoProjectRepository projects;
    private final VideoSceneRepository scenes;
    private final MediaFileRepository files;
    private final TextExtractor extractor;
    private final VoiceCatalog voices;
    private final AppProperties props;
    private final ApplicationEventPublisher events;

    public ProjectService(VideoProjectRepository projects, VideoSceneRepository scenes, MediaFileRepository files,
                          TextExtractor extractor, VoiceCatalog voices, AppProperties props, ApplicationEventPublisher events) {
        this.projects = projects;
        this.scenes = scenes;
        this.files = files;
        this.extractor = extractor;
        this.voices = voices;
        this.props = props;
        this.events = events;
    }

    public record CreateCommand(MultipartFile file, String title, int lengthMinutes, String style, boolean narration,
                                boolean music, boolean subtitles, String voiceLanguage, String voiceType) {}

    @Transactional
    public ProjectDto create(CreateCommand c) {
        MultipartFile f = c.file();
        if (f == null || f.isEmpty()) throw ApiException.badRequest("Please choose a document to upload");
        if (f.getSize() > props.maxUploadBytes()) throw ApiException.badRequest("File is too large (max 50 MB)");
        String title = c.title() == null ? "" : c.title().strip();
        if (title.isEmpty()) throw ApiException.badRequest("Please enter a title for your video");
        if (title.length() > 100) throw ApiException.badRequest("Title must be 100 characters or fewer");
        if (!LENGTHS.contains(c.lengthMinutes())) throw ApiException.badRequest("Unsupported video length");
        VideoStyle style;
        try {
            style = VideoStyle.valueOf(c.style().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Unknown style");
        }
        String gender = "female".equalsIgnoreCase(c.voiceType()) ? "female" : "male";

        byte[] data;
        try {
            data = f.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
        String name = sanitizeFilename(f.getOriginalFilename());
        TextExtractor.Format fmt = TextExtractor.detect(data, name);
        if (fmt == null) throw ApiException.badRequest("Unsupported file. Please upload a PDF, DOC, DOCX or TXT document");

        MediaFile source = files.save(new MediaFile(name, fmt.contentType, data));
        VideoProject p = projects.save(new VideoProject(title, c.lengthMinutes(), style, c.narration(), c.music(), c.subtitles(),
                c.voiceLanguage() == null || c.voiceLanguage().isBlank() ? "en-GB" : c.voiceLanguage(), gender,
                name, data.length, source.getId()));
        events.publishEvent(new GenerationPipeline.ProjectCreated(p.getId()));
        return toDto(p, List.of());
    }

    @Transactional(readOnly = true)
    public ProjectDto get(UUID id) {
        return toDto(find(id), true);
    }

    @Transactional(readOnly = true)
    public ProjectDto getByToken(String token) {
        return toDto(projects.findByShareToken(token).orElseThrow(() -> ApiException.notFound("Video not found")), true);
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> recent(int limit) {
        return projects.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(p -> toDto(p, List.of())).toList();
    }

    @Transactional
    public void delete(UUID id) {
        VideoProject p = find(id);
        if (!p.getStatus().isTerminal()) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                "This video is still being generated");
        List<VideoScene> sc = scenes.findByProjectIdOrderByIdx(id);
        scenes.deleteByProjectId(id);
        projects.delete(p);
        projects.flush();
        sc.stream().map(VideoScene::getThumbnailFileId).filter(java.util.Objects::nonNull).forEach(files::deleteById);
        if (p.getVideoFileId() != null) files.deleteById(p.getVideoFileId());
        files.deleteById(p.getSourceFileId());
    }

    @Transactional(readOnly = true)
    public UUID videoFileId(UUID id) {
        return requireVideo(find(id));
    }

    @Transactional(readOnly = true)
    public UUID videoFileIdByToken(String token) {
        return requireVideo(projects.findByShareToken(token).orElseThrow(() -> ApiException.notFound("Video not found")));
    }

    @Transactional(readOnly = true)
    public UUID sourceFileId(UUID id) {
        return find(id).getSourceFileId();
    }

    /** Anything still running when the app stopped can never finish; mark it so the UI stops polling. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failInterrupted() {
        List<VideoProject> stuck = projects.findByStatusIn(EnumSet.complementOf(EnumSet.of(ProjectStatus.COMPLETED, ProjectStatus.FAILED)));
        stuck.forEach(p -> p.fail("Generation was interrupted by an application restart. Please generate again."));
        if (!stuck.isEmpty()) log.warn("Marked {} interrupted project(s) as failed", stuck.size());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private VideoProject find(UUID id) {
        return projects.findById(id).orElseThrow(() -> ApiException.notFound("Video not found"));
    }

    private static UUID requireVideo(VideoProject p) {
        if (p.getVideoFileId() == null) throw ApiException.notFound("The video is not ready yet");
        return p.getVideoFileId();
    }

    private ProjectDto toDto(VideoProject p, boolean withScenes) {
        return toDto(p, withScenes ? scenes.findByProjectIdOrderByIdx(p.getId()).stream().map(this::toDto).toList() : List.of());
    }

    private SceneDto toDto(VideoScene s) {
        return new SceneDto(s.getId(), s.getIdx(), s.getTitle(), s.getNarration(), s.getStartSeconds(), s.getDurationSeconds(),
                s.getThumbnailFileId() == null ? null : "/api/media/" + s.getThumbnailFileId() + "/thumbnail");
    }

    private ProjectDto toDto(VideoProject p, List<SceneDto> sc) {
        boolean ready = p.getVideoFileId() != null;
        return new ProjectDto(p.getId(), p.getTitle(), p.getStatus().name(), p.getProgress(), p.getMessage(), p.getError(),
                p.getLengthMinutes(), p.getStyle().name(), p.isNarration(), p.isMusic(), p.isSubtitles(), p.getVoiceLanguage(),
                p.getVoiceType(), p.getSourceFilename(), p.getSourceSize(), p.getDurationSeconds(),
                ready ? "/api/projects/" + p.getId() + "/video" : null,
                ready ? "/api/projects/" + p.getId() + "/video?download=true" : null,
                p.getShareToken(), p.getCreatedAt(), sc);
    }

    private static String sanitizeFilename(String original) {
        String n = original == null ? "document" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (n.isEmpty()) n = "document";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
