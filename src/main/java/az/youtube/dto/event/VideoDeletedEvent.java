package az.youtube.dto.event;

public record VideoDeletedEvent(long videoId, String originalS3Key) {

    public static final String TYPE = "VideoDeleted";
}
