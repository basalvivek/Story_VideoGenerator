CREATE TABLE media_file (
    id            UUID PRIMARY KEY,
    filename      VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    data          BYTEA        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- external storage = uncompressed out-of-line, so substring() range reads are cheap
ALTER TABLE media_file ALTER COLUMN data SET STORAGE EXTERNAL;

CREATE TABLE video_project (
    id               UUID PRIMARY KEY,
    title            VARCHAR(100) NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    progress         INT          NOT NULL DEFAULT 0,
    message          VARCHAR(255),
    error            TEXT,
    length_minutes   INT          NOT NULL,
    style            VARCHAR(30)  NOT NULL,
    narration        BOOLEAN      NOT NULL,
    music            BOOLEAN      NOT NULL,
    subtitles        BOOLEAN      NOT NULL,
    voice_language   VARCHAR(20)  NOT NULL,
    voice_type       VARCHAR(20)  NOT NULL,
    source_filename  VARCHAR(255) NOT NULL,
    source_size      BIGINT       NOT NULL,
    source_file_id   UUID         NOT NULL REFERENCES media_file(id),
    video_file_id    UUID REFERENCES media_file(id),
    duration_seconds DOUBLE PRECISION,
    share_token      VARCHAR(40)  NOT NULL UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_project_created ON video_project (created_at DESC);

CREATE TABLE video_scene (
    id                 UUID PRIMARY KEY,
    project_id         UUID NOT NULL REFERENCES video_project(id) ON DELETE CASCADE,
    idx                INT  NOT NULL,
    title              VARCHAR(120) NOT NULL,
    narration          TEXT NOT NULL,
    start_seconds      DOUBLE PRECISION NOT NULL,
    duration_seconds   DOUBLE PRECISION NOT NULL,
    thumbnail_file_id  UUID REFERENCES media_file(id)
);
CREATE INDEX idx_scene_project ON video_scene (project_id, idx);
