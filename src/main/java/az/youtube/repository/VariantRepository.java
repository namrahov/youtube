package az.youtube.repository;

import az.youtube.entity.Resolution;
import az.youtube.entity.VariantStatus;

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

    public record Variant(Resolution resolution, VariantStatus status, String s3Key, int attempts, String lastError) {
    }

    private final JdbcClient jdbc;

    public VariantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(long videoId, Resolution resolution) {
        jdbc.sql("""
                        INSERT INTO video_variants (video_id, resolution, status)
                        VALUES (:videoId, :resolution, 'PENDING')
                        ON CONFLICT (video_id, resolution) DO NOTHING
                        """)
                .param("videoId", videoId)
                .param("resolution", resolution.label())
                .update();
    }

    /**
     * Takes the job for a worker. PROCESSING is accepted too: that is a redelivery after a worker died mid-job.
     *
     * @return false if the variant is already COMPLETED or FAILED (a duplicate job), or does not exist
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
     * @return 1 if this call completed the variant, 0 if it was already COMPLETED (duplicate event)
     */
    public int markCompleted(long videoId, Resolution resolution, String s3Key) {
        return jdbc.sql("""
                        UPDATE video_variants
                        SET status = 'COMPLETED', s3_key = :s3Key, last_error = NULL, updated_at = now()
                        WHERE video_id = :videoId AND resolution = :resolution AND status <> 'COMPLETED'
                        """)
                .param("videoId", videoId)
                .param("resolution", resolution.label())
                .param("s3Key", s3Key)
                .update();
    }

    /** @return 1 if this call failed the variant, 0 if it was already final */
    public int markFailed(long videoId, Resolution resolution, String error) {
        return jdbc.sql("""
                        UPDATE video_variants
                        SET status = 'FAILED', last_error = :error, updated_at = now()
                        WHERE video_id = :videoId AND resolution = :resolution
                          AND status NOT IN ('COMPLETED', 'FAILED')
                        """)
                .param("videoId", videoId)
                .param("resolution", resolution.label())
                .param("error", error)
                .update();
    }

    public List<Variant> findByVideo(long videoId) {
        return jdbc.sql("""
                        SELECT resolution, status, s3_key, attempts, last_error
                        FROM video_variants WHERE video_id = :videoId
                        """)
                .param("videoId", videoId)
                .query((rs, i) -> new Variant(
                        Resolution.fromLabel(rs.getString("resolution")),
                        VariantStatus.valueOf(rs.getString("status")),
                        rs.getString("s3_key"),
                        rs.getInt("attempts"),
                        rs.getString("last_error")))
                .list()
                .stream()
                .sorted((a, b) -> a.resolution().compareTo(b.resolution()))
                .toList();
    }

    public Optional<Variant> find(long videoId, Resolution resolution) {
        return findByVideo(videoId).stream().filter(v -> v.resolution() == resolution).findFirst();
    }
}
