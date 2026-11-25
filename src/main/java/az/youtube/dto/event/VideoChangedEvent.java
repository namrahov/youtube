package az.youtube.dto.event;

/**
 * Only the id: the indexer reads the video's current state from the database, so an old or repeated event cannot
 * put stale data into the index.
 */
public record VideoChangedEvent(long videoId) {

    public static final String TYPE = "VideoChanged";
}
