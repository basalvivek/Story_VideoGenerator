package com.moviemaker.repo;

import com.moviemaker.domain.MediaFile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {
    interface Meta {
        String getFilename();
        String getContentType();
        long getSizeBytes();
    }

    @Query("select m.filename as filename, m.contentType as contentType, m.sizeBytes as sizeBytes from MediaFile m where m.id = :id")
    Optional<Meta> findMeta(UUID id);
}
