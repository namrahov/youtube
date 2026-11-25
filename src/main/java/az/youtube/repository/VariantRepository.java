package az.youtube.repository;

import az.youtube.entity.Resolution;
import az.youtube.entity.VariantStatus;
import az.youtube.transcoding.Rendition;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * video_variants with plain SQL: every write is conditional on the current status, which is what makes duplicate
 * Kafka deliveries harmless (a second "completed" for the same variant updates 0 rows).
 */
@Repository
public class VariantRepository {

    /** The rendition fields are set once the variant is COMPLETED (null for variants finished before they existed). */
    public record Variant(Resolution resolution, VariantStatus status, String s3Key, int attempts, String lastError,
                          Integer width, Integer height, Long bandwidth, Long averageBandwidth, String codecs) {
    }

    private final JdbcClient jdbc;

    public VariantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(long videoId, Resolution resolution) {
        insert(videoId, resolution, VariantStatus.PENDING);
    }

    /** A rendition larger than the source: recorded so the API shows why it is missing, but never encoded. */
    public void insertSkipped(long videoId, Resolution resolution) {
        insert(videoId, resolution, VariantStatus.SKIPPED);
    }

    private void insert(long videoId, Resolution resolution, VariantStatus status) {
        jdbc.sql("""
                        INSERT INTO video_variants (video_id, resolution, status)
                        VALUES (:videoId, :resolution, :status)
                        ON CONFLICT (video_id, resolution) DO NOTHING
                        """)
                .param("videoId", videoId)
                .param("resolution", resolution.label())
                .param("status", status.name())
                .update();
    }

    /**
     * Takes the job for a worker. PROCESSING is accepted too: that is a redelivery after a worker died mid-job.
     * A job still running elsewhere is not redelivered: the worker pool's max.poll.interval.ms is longer than the job
     * timeout (see KafkaConfig).
     *
     * @return false if the variant is already COMPLETED, FAILED or SKIPPED (a duplicate job), or does not exist
     */
    public boolean claim(long videoId, Resolution resolution) {
        return jdbc.sql("""
                        UPDATE video_variants
                        SET status = 'PROCESSING', attempts = attempts + 1, updated_at = now()
                        WHERE video_id = :videoId AND resolution = :resolution
                          AND status IN ('PENDING', 'PROCESSING')
                        """)
                .param("videoId", videoId)
                .param("resolution", resolution.label())
                .update() == 1;
    }

    /**
     * FAILED → COMPLETED is allowed on purpose: a slow worker may finish after the error handler gave up on it.
     * The rendition exists, so it is used.
     *
     * @return 1 if this call completed the variant, 0 if it was already COMPLETED (duplicate event) or SKIPPED
     */
    public int markCompleted(long videoId, Resolution resolution, Rendition rendition) {
        return jdbc.sql("""
                        UPDATE video_variants
                        SET status = 'COMPLETED', s3_key = :s3Key, last_error = NULL, width = :width, height = :height,
                            bandwidth = :bandwidth, average_bandwidth = :averageBandwidth, codecs = :codecs,
                            updated_at = now()
                        WHERE video_id = :videoId AND resolution = :resolution
                          AND status NOT IN ('COMPLETED', 'SKIPPED')
                        """)
                .param("videoId", videoId)
                .param("resolution", resolution.label())
                .param("s3Key", rendition.playlistKey())
                .param("width", rendition.width())
                .param("height", rendition.height())
                .param("bandwidth", rendition.bandwidth())
                .param("averageBandwidth", rendition.averageBandwidth())
                .param("codecs", rendition.codecs())
                .update();
    }

    /** @return 1 if this call failed the variant, 0 if it was already final */
    public int markFailed(long videoId, Resolution resolution, String error) {
        return jdbc.sql("""
                        UPDATE video_variants
                        SET status = 'FAILED', last_error = :error, updated_at = now()
                        WHERE video_id = :videoId AND resolution = :resolution
                          AND status NOT IN ('COMPLETED', 'FAILED', 'SKIPPED')
                        """)
                .param("videoId", videoId)
                .param("resolution", resolution.label())
                .param("error", error)
                .update();
    }

    public List<Variant> findByVideo(long videoId) {
        return jdbc.sql("""
                        SELECT resolution, status, s3_key, attempts, last_error,
                               width, height, bandwidth, average_bandwidth, codecs
                        FROM video_variants WHERE video_id = :videoId
                        """)
                .param("videoId", videoId)
                .query((rs, i) -> new Variant(
                        Resolution.fromLabel(rs.getString("resolution")),
                        VariantStatus.valueOf(rs.getString("status")),
                        rs.getString("s3_key"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getObject("width", Integer.class),
                        rs.getObject("height", Integer.class),
                        rs.getObject("bandwidth", Long.class),
                        rs.getObject("average_bandwidth", Long.class),
                        rs.getString("codecs")))
                .list()
                .stream()
                .sorted((a, b) -> a.resolution().compareTo(b.resolution()))
                .toList();
    }

    public Optional<Variant> find(long videoId, Resolution resolution) {
        return findByVideo(videoId).stream().filter(v -> v.resolution() == resolution).findFirst();
    }
}
