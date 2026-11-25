package az.youtube.entity;

/**
 * State of one (video, resolution) transcoding job. COMPLETED, FAILED and SKIPPED are final.
 * SKIPPED: the source is smaller than this rendition (no upscaling); no job is created for it.
 */
public enum VariantStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    SKIPPED
}
