package com.moviemaker.service;

import com.moviemaker.domain.ProjectStatus;
import com.moviemaker.repo.VideoProjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Small independent transactions so the UI sees progress while the long-running pipeline is still executing. */
@Service
public class ProgressService {
    private final VideoProjectRepository projects;

    public ProgressService(VideoProjectRepository projects) {
        this.projects = projects;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(UUID id, ProjectStatus status, int percent, String message) {
        projects.findById(id).ifPresent(p -> p.progress(status, Math.min(99, percent), message));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID id, UUID videoFileId, double seconds) {
        projects.findById(id).ifPresent(p -> p.complete(videoFileId, seconds));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID id, String error) {
        projects.findById(id).ifPresent(p -> p.fail(error));
    }
}
