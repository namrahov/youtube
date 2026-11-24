package az.youtube.entity;

/** State of one (video, resolution) transcoding job. COMPLETED and FAILED are final. */
public enum VariantStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
