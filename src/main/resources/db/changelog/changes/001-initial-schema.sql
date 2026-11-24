--liquibase formatted sql

--changeset youtube:001-videos
-- One row per upload, created before the first byte is sent (status UPLOADING): the control point for
-- timeouts, retries and orphan multipart uploads.
CREATE SEQUENCE videos_id_seq;
CREATE TABLE videos (
    -- Taken from videos_id_seq before the insert, so the row is written together with its S3 key videos/{id}/original.
    id                BIGINT       PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    title             VARCHAR(200) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL CHECK (size_bytes > 0),
    part_size_bytes   BIGINT       NOT NULL CHECK (part_size_bytes > 0),
    part_count        INT          NOT NULL CHECK (part_count BETWEEN 1 AND 10000),
    original_s3_key   VARCHAR(500) NOT NULL UNIQUE,
    s3_upload_id      VARCHAR(1024),
    status            VARCHAR(30)  NOT NULL,
    manifest_s3_key   VARCHAR(500),
    upload_expires_at TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT videos_status_check CHECK (status IN ('UPLOADING', 'UPLOADED', 'UPLOAD_FAILED', 'PROCESSING',
                                                     'PLAYABLE', 'FULLY_PROCESSED', 'PROCESSING_FAILED'))
);
CREATE INDEX idx_videos_user ON videos (user_id, id DESC);
-- The expiry sweep looks only at unfinished uploads, not at the whole table.
CREATE INDEX idx_videos_upload_expiry ON videos (upload_expires_at) WHERE status = 'UPLOADING';

--changeset youtube:001-video-variants
-- One row per (video, resolution). Each transcoding job owns its row, so a duplicate "completed" event rewrites
-- the same row instead of incrementing a counter, and the video status is derived from these rows.
CREATE TABLE video_variants (
    video_id   BIGINT      NOT NULL REFERENCES videos (id),
    resolution VARCHAR(10) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    s3_key     TEXT,
    attempts   INT         NOT NULL DEFAULT 0,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (video_id, resolution),
    CONSTRAINT video_variants_status_check CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

--changeset youtube:001-outbox
-- Transactional outbox: an event is written in the same transaction as the state change it announces and
-- published to Kafka later by OutboxPublisher. While Kafka is down the event waits here instead of being lost.
CREATE TABLE outbox (
    id           BIGSERIAL    PRIMARY KEY,
    aggregate_id BIGINT       NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(200) NOT NULL,
    kafka_key    VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0
);
-- The publisher reads only unpublished rows, oldest first: a small partial index instead of scanning the table.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
