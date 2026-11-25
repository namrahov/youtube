package az.youtube.messaging;

/** Kafka topic names. Constants, so they can be used in {@code @KafkaListener} annotations. */
public final class Topics {

    /** A verified upload; keyed by video id. Consumed by the job planner. */
    public static final String VIDEO_UPLOADED = "video.uploaded";

    /*
     * One topic (and one consumer group = one worker pool) per resolution. With a single topic, the four jobs of a
     * video keyed by videoId would all land on one partition and run one after another; keying by videoId:resolution
     * spreads them only by chance (hash collisions). Separate topics give every resolution its own parallelism,
     * scaling and resource profile: a 4K job is far heavier than a 360p one.
     */
    public static final String TRANSCODE_360P = "video.transcode.360p";
    public static final String TRANSCODE_720P = "video.transcode.720p";
    public static final String TRANSCODE_1080P = "video.transcode.1080p";
    public static final String TRANSCODE_4K = "video.transcode.4k";

    /** The searchable state of a video changed (became playable, deleted); keyed by video id. Search indexer. */
    public static final String VIDEO_CHANGED = "video.changed";

    /** The owner deleted a video; keyed by video id. Storage cleaner. */
    public static final String VIDEO_DELETED = "video.deleted";

    /**
     * One counted-worthy view per message, keyed by video id: all views of a video go to one partition and so to
     * one aggregator, which is then the only writer of that video's counter (no row contention, no lost updates).
     */
    public static final String VIDEO_VIEWS = "video.views";

    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    public static boolean isTranscodeTopic(String topic) {
        return topic.startsWith("video.transcode.");
    }
}
