package com.moviemaker.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "video_scene")
public class VideoScene {
    @Id
    private UUID id;
    @Column(name = "project_id", nullable = false)
    private UUID projectId;
    @Column(nullable = false)
    private int idx;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String narration;
    @Column(name = "start_seconds", nullable = false)
    private double startSeconds;
    @Column(name = "duration_seconds", nullable = false)
    private double durationSeconds;
    @Column(name = "thumbnail_file_id")
    private UUID thumbnailFileId;

    protected VideoScene() {}

    public VideoScene(UUID projectId, int idx, String title, String narration,
                      double startSeconds, double durationSeconds, UUID thumbnailFileId) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.idx = idx;
        this.title = title;
        this.narration = narration;
        this.startSeconds = startSeconds;
        this.durationSeconds = durationSeconds;
        this.thumbnailFileId = thumbnailFileId;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public int getIdx() { return idx; }
    public String getTitle() { return title; }
    public String getNarration() { return narration; }
    public double getStartSeconds() { return startSeconds; }
    public double getDurationSeconds() { return durationSeconds; }
    public UUID getThumbnailFileId() { return thumbnailFileId; }
}
