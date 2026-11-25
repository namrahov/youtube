package az.youtube.entity;

/**
 * Lifecycle of a video:
 * {@code UPLOADING → UPLOADED → PROCESSING → PLAYABLE → FULLY_PROCESSED}, and from any of them {@code DELETED}.
 * <ul>
 *   <li>{@code UPLOAD_FAILED}: aborted by the owner, expired, or the uploaded object failed verification.</li>
 *   <li>{@code PLAYABLE}: at least one variant and the master manifest are ready; others may still be running.</li>
 *   <li>{@code FULLY_PROCESSED}: every required variant COMPLETED.</li>
 *   <li>{@code PROCESSING_FAILED}: not a readable video, or every variant FAILED: nothing can be played.</li>
 *   <li>{@code DELETED}: removed by the owner. Invisible to everyone; its files are deleted asynchronously.</li>
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
    PROCESSING_FAILED,
    DELETED;

    public boolean isPlayable() {
        return this == PLAYABLE || this == FULLY_PROCESSED;
    }
}
