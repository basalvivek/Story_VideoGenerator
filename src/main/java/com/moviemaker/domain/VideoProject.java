package com.moviemaker.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "video_project")
public class VideoProject {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.QUEUED;
    private int progress;
    private String message;
    @Column(columnDefinition = "text")
    private String error;
    @Column(name = "length_minutes", nullable = false)
    private int lengthMinutes;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoStyle style;
    private boolean narration;
    private boolean music;
    private boolean subtitles;
    @Column(name = "voice_language", nullable = false)
    private String voiceLanguage;
    @Column(name = "voice_type", nullable = false)
    private String voiceType;
    @Column(name = "source_filename", nullable = false)
    private String sourceFilename;
    @Column(name = "source_size", nullable = false)
    private long sourceSize;
    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;
    @Column(name = "video_file_id")
    private UUID videoFileId;
    @Column(name = "duration_seconds")
    private Double durationSeconds;
    @Column(name = "share_token", nullable = false, unique = true)
    private String shareToken;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VideoProject() {}

    public VideoProject(String title, int lengthMinutes, VideoStyle style, boolean narration, boolean music,
                        boolean subtitles, String voiceLanguage, String voiceType,
                        String sourceFilename, long sourceSize, UUID sourceFileId) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.lengthMinutes = lengthMinutes;
        this.style = style;
        this.narration = narration;
        this.music = music;
        this.subtitles = subtitles;
        this.voiceLanguage = voiceLanguage;
        this.voiceType = voiceType;
        this.sourceFilename = sourceFilename;
        this.sourceSize = sourceSize;
        this.sourceFileId = sourceFileId;
        this.shareToken = UUID.randomUUID().toString().replace("-", "");
        this.message = "Queued";
    }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public void progress(ProjectStatus status, int progress, String message) {
        this.status = status;
        this.progress = progress;
        this.message = message;
    }

    public void complete(UUID videoFileId, double durationSeconds) {
        this.videoFileId = videoFileId;
        this.durationSeconds = durationSeconds;
        progress(ProjectStatus.COMPLETED, 100, "Video ready");
    }

    public void fail(String error) {
        this.error = error;
        progress(ProjectStatus.FAILED, progress, "Generation failed");
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public ProjectStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getMessage() { return message; }
    public String getError() { return error; }
    public int getLengthMinutes() { return lengthMinutes; }
    public VideoStyle getStyle() { return style; }
    public boolean isNarration() { return narration; }
    public boolean isMusic() { return music; }
    public boolean isSubtitles() { return subtitles; }
    public String getVoiceLanguage() { return voiceLanguage; }
    public String getVoiceType() { return voiceType; }
    public String getSourceFilename() { return sourceFilename; }
    public long getSourceSize() { return sourceSize; }
    public UUID getSourceFileId() { return sourceFileId; }
    public UUID getVideoFileId() { return videoFileId; }
    public Double getDurationSeconds() { return durationSeconds; }
    public String getShareToken() { return shareToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
