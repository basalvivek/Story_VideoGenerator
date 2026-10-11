package com.moviemaker.repo;

import com.moviemaker.domain.ProjectStatus;
import com.moviemaker.domain.VideoProject;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoProjectRepository extends JpaRepository<VideoProject, UUID> {
    List<VideoProject> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Optional<VideoProject> findByShareToken(String token);
    List<VideoProject> findByStatusIn(Collection<ProjectStatus> statuses);
}
