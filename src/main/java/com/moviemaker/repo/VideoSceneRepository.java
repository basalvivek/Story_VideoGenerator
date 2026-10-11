package com.moviemaker.repo;

import com.moviemaker.domain.VideoScene;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoSceneRepository extends JpaRepository<VideoScene, UUID> {
    List<VideoScene> findByProjectIdOrderByIdx(UUID projectId);
    void deleteByProjectId(UUID projectId);
}
