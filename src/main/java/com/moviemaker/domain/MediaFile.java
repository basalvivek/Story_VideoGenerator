package com.moviemaker.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_file")
public class MediaFile {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String filename;
    @Column(name = "content_type", nullable = false)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(nullable = false)
    private byte[] data;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MediaFile() {}

    public MediaFile(String filename, String contentType, byte[] data) {
        this.id = UUID.randomUUID();
        this.filename = filename;
        this.contentType = contentType;
        this.data = data;
        this.sizeBytes = data.length;
    }

    public UUID getId() { return id; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
}
