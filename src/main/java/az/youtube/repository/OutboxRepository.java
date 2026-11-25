package az.youtube.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The outbox table. {@link #append} runs inside the caller's transaction (same connection as the JPA changes);
 * the queue methods are single auto-committed statements, so no lock is held while the publisher waits for Kafka.
 */
@Repository
public class OutboxRepository {

    public record OutboxMessage(long id, String topic, String kafkaKey, String eventType, String payload) {
    }

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(long aggregateId, String eventType, String topic, String kafkaKey, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_id, event_type, topic, kafka_key, payload)
                        VALUES (:aggregateId, :eventType, :topic, :kafkaKey, CAST(:payload AS jsonb))
                        """)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("topic", topic)
                .param("kafkaKey", kafkaKey)
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Claims up to {@code limit} of the oldest unpublished events for {@code claimSeconds}. SKIP LOCKED lets several
     * publisher instances take different rows; a claim whose publisher crashed expires and the rows are taken again.
     * Only unpublished rows are read (partial index), never the whole table.
     */
    public List<OutboxMessage> claim(int limit, int claimSeconds) {
        return jdbc.sql("""
                        UPDATE outbox o
                        SET locked_until = now() + make_interval(secs => :claimSeconds)
                        WHERE o.id IN (
                            SELECT id FROM outbox
                            WHERE published_at IS NULL AND (locked_until IS NULL OR locked_until < now())
                            ORDER BY id
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING o.id, o.topic, o.kafka_key, o.event_type, o.payload::text AS payload
                        """)
                .param("claimSeconds", claimSeconds)
                .param("limit", limit)
                .query((rs, i) -> new OutboxMessage(rs.getLong("id"), rs.getString("topic"),
                        rs.getString("kafka_key"), rs.getString("event_type"), rs.getString("payload")))
                .list()
                .stream()
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .toList();
    }

    public record PendingStats(long count, long oldestAgeSeconds) {
    }

    /** Unpublished events and the age of the oldest one; reads only the partial index of unpublished rows. */
    public PendingStats pendingStats() {
        return jdbc.sql("""
                        SELECT count(*) AS pending,
                               coalesce(extract(epoch FROM now() - min(created_at)), 0)::bigint AS oldest_age
                        FROM outbox WHERE published_at IS NULL
                        """)
                .query((rs, i) -> new PendingStats(rs.getLong("pending"), rs.getLong("oldest_age")))
                .single();
    }

    public void markPublished(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox SET published_at = now(), locked_until = NULL WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }

    /** Gives failed events back to the queue at once instead of waiting for the claim to expire. */
    public void release(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox SET locked_until = NULL, attempts = attempts + 1 WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }
}
