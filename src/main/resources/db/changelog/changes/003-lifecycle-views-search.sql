--liquibase formatted sql

--changeset youtube:003-video-lifecycle
-- uploaded_at: the upload was verified (start of the "time to playable" metric).
-- published_at: the video first became playable; search ranks by it.
-- deleted_at: soft delete by the owner. The row stays (views and outbox rows refer to it), the files go.
-- storage_purged_at: the files of a deleted or failed video have been removed from object storage.
ALTER TABLE videos
    ADD COLUMN uploaded_at       TIMESTAMPTZ,
    ADD COLUMN published_at      TIMESTAMPTZ,
    ADD COLUMN deleted_at        TIMESTAMPTZ,
    ADD COLUMN storage_purged_at TIMESTAMPTZ;
ALTER TABLE videos DROP CONSTRAINT videos_status_check;
ALTER TABLE videos ADD CONSTRAINT videos_status_check
    CHECK (status IN ('UPLOADING', 'UPLOADED', 'UPLOAD_FAILED', 'PROCESSING', 'PLAYABLE', 'FULLY_PROCESSED',
                      'PROCESSING_FAILED', 'DELETED'));
-- The retention sweep looks only at failed videos whose original is still stored.
CREATE INDEX idx_videos_failed_unpurged ON videos (updated_at)
    WHERE status = 'PROCESSING_FAILED' AND storage_purged_at IS NULL;

--changeset youtube:003-variant-backlog-index
-- The processing backlog gauge counts open jobs per resolution without scanning every finished variant.
CREATE INDEX idx_video_variants_open ON video_variants (resolution) WHERE status IN ('PENDING', 'PROCESSING');

--changeset youtube:003-video-stats
-- View counters, apart from the wide videos row: a counter is updated all the time, the video row rarely.
-- No index on view_count or updated_at, and fillfactor 70 leaves room in each page: an UPDATE then writes the
-- new row version into the same page without touching any index (a HOT update), which keeps a hot counter cheap.
CREATE TABLE video_stats (
    video_id   BIGINT      PRIMARY KEY REFERENCES videos (id),
    view_count BIGINT      NOT NULL DEFAULT 0 CHECK (view_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
) WITH (fillfactor = 70);

--changeset youtube:003-consumer-offsets
-- Kafka offsets of consumers whose effect is not idempotent (adding to a counter). The offset is committed in the
-- same transaction as the counters, so after a crash the batch is either fully counted and skipped, or not counted
-- and read again: never counted twice.
CREATE TABLE consumer_offsets (
    consumer    VARCHAR(100) NOT NULL,
    topic       VARCHAR(200) NOT NULL,
    partition   INT          NOT NULL,
    next_offset BIGINT       NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, topic, partition)
);

--changeset youtube:003-backfill-search
-- Videos that were already playable before search existed: give them a publication time and queue them for the
-- indexer through the outbox, exactly as a newly published video would be.
UPDATE videos SET published_at = updated_at
WHERE status IN ('PLAYABLE', 'FULLY_PROCESSED') AND published_at IS NULL;
INSERT INTO outbox (aggregate_id, event_type, topic, kafka_key, payload)
SELECT id, 'VideoChanged', 'video.changed', id::text, jsonb_build_object('videoId', id)
FROM videos WHERE status IN ('PLAYABLE', 'FULLY_PROCESSED');
