package az.youtube.entity;

/**
 * Lifecycle of a video:
 * {@code UPLOADING → UPLOADED → PROCESSING → PLAYABLE → FULLY_PROCESSED}.
 * <ul>
 *   <li>{@code UPLOAD_FAILED}: aborted by the owner, expired, or the uploaded object failed verification.</li>
 *   <li>{@code PLAYABLE}: at least one variant and the master manifest are ready; others may still be running.</li>
 *   <li>{@code FULLY_PROCESSED}: every requested variant COMPLETED.</li>
 *   <li>{@code PROCESSING_FAILED}: every variant FAILED, nothing can be played.</li>
 * </ul>
 * A video that is PLAYABLE with a FAILED variant stays PLAYABLE: it plays with the variants it has.
 */
public enum VideoStatus {
    UPLOADING,
    UPLOADED,
    UPLOAD_FAILED,
    PROCESSING,
    PLAYABLE,
    FULLY_PROCESSED,
    PROCESSING_FAILED;

    public boolean isPlayable() {
        return this == PLAYABLE || this == FULLY_PROCESSED;
    }
}
