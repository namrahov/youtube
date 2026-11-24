package az.youtube.dto.event;

/** Written to the outbox in the transaction that moves the video to UPLOADED. */
public record VideoUploadedEvent(long videoId, String originalS3Key) {

    public static final String TYPE = "VideoUploaded";
}
