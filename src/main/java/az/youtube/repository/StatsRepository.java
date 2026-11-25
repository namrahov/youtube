package az.youtube.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** video_stats counters and the Kafka offsets committed together with them. */
@Repository
public class StatsRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public StatsRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Adds each video's views of one batch with one statement per video, however many views it had: a viral video's
     * 10 000 views a second become one UPDATE per batch. A video id with no row in videos is ignored (the SELECT
     * finds nothing) instead of failing the batch forever on the foreign key.
     */
    public void addViews(Map<Long, Long> counts) {
        if (counts.isEmpty()) {
            return;
        }
        List<Object[]> rows = counts.entrySet().stream()
                .map(e -> new Object[] {e.getValue(), e.getKey()})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO video_stats (video_id, view_count)
                SELECT id, ? FROM videos WHERE id = ?
                ON CONFLICT (video_id) DO UPDATE
                SET view_count = video_stats.view_count + EXCLUDED.view_count, updated_at = now()
                """, rows);
    }

    public long viewCount(long videoId) {
        return jdbc.sql("SELECT view_count FROM video_stats WHERE video_id = :id")
                .param("id", videoId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public Map<Long, Long> viewCounts(Collection<Long> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT video_id, view_count FROM video_stats WHERE video_id IN (:ids)")
                .param("ids", videoIds)
                .query((rs, i) -> Map.entry(rs.getLong("video_id"), rs.getLong("view_count")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** @return next offset to read per partition, as last committed with the counters */
    public Map<Integer, Long> loadOffsets(String consumer, String topic) {
        return jdbc.sql("SELECT partition, next_offset FROM consumer_offsets WHERE consumer = :consumer AND topic = :topic")
                .param("consumer", consumer)
                .param("topic", topic)
                .query((rs, i) -> Map.entry(rs.getInt("partition"), rs.getLong("next_offset")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void saveOffsets(String consumer, String topic, Map<Integer, Long> nextOffsets) {
        List<Object[]> rows = nextOffsets.entrySet().stream()
                .map(e -> new Object[] {consumer, topic, e.getKey(), e.getValue()})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO consumer_offsets (consumer, topic, partition, next_offset) VALUES (?, ?, ?, ?)
                ON CONFLICT (consumer, topic, partition) DO UPDATE
                SET next_offset = EXCLUDED.next_offset, updated_at = now()
                """, rows);
    }
}
