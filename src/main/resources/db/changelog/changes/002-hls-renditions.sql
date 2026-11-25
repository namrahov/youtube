--liquibase formatted sql

--changeset youtube:002-video-source
-- What ffprobe found in the original before the jobs were planned (displayed size, i.e. after rotation).
-- NULL: not probed (simulated engine, or a video planned before this change).
ALTER TABLE videos
    ADD COLUMN source_width       INT,
    ADD COLUMN source_height      INT,
    ADD COLUMN source_duration_ms BIGINT;

--changeset youtube:002-variant-rendition
-- What the master playlist announces for a COMPLETED variant: the real output size, the measured peak and average
-- bit rate of its segments and its RFC 6381 codec string. NULL for variants finished before this change.
ALTER TABLE video_variants
    ADD COLUMN width             INT,
    ADD COLUMN height            INT,
    ADD COLUMN bandwidth         BIGINT,
    ADD COLUMN average_bandwidth BIGINT,
    ADD COLUMN codecs            VARCHAR(100);

--changeset youtube:002-variant-skipped
-- SKIPPED: the source is smaller than this rendition, so it is not made (no upscaling) and not required.
ALTER TABLE video_variants DROP CONSTRAINT video_variants_status_check;
ALTER TABLE video_variants ADD CONSTRAINT video_variants_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'SKIPPED'));
