package az.youtube.views;

import az.youtube.config.ViewsProperties;
import az.youtube.dto.event.ViewEvent;
import az.youtube.messaging.Topics;
import az.youtube.metrics.AppMetrics;
import az.youtube.repository.StatsRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the stream of views into counters, in batches:
 * <ol>
 *   <li>one poll = one batch (up to {@code max-batch} views, or what arrived within {@code batch-wait-ms});</li>
 *   <li>deduplicate: in the batch, and against Redis (eventIds and viewers seen in earlier batches);</li>
 *   <li>one transaction: add each video's count with one statement, and store the next Kafka offset per partition;</li>
 *   <li>after the commit: remember the new keys in Redis and mark the videos for the search index.</li>
 * </ol>
 * <b>Never counted twice.</b> Adding to a counter is not idempotent, so a redelivered batch must not be added again.
 * The offsets live in the same transaction as the counters, and on partition assignment the consumer seeks to the
 * stored offset. A crash before the commit: nothing counted, the batch is read again. A crash after the commit but
 * before Kafka's own offset commit: the stored offset skips the batch. A crash before the Redis write: same, the
 * batch is skipped, so its keys are simply not remembered (at worst a later repeat view of that user counts).
 * <p>
 * <b>No hot row.</b> The topic is keyed by video id, so all views of a video are counted by one consumer: its counter
 * has a single writer, updated once per batch. A viral video's 10 000 views a second are one UPDATE every
 * {@code batch-wait-ms}, and counting them in memory is trivial for one thread. Sharded counters (N rows per video)
 * are what you need when many writers update the same counter; this design does not have them.
 */
@Component
public class ViewAggregator implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(ViewAggregator.class);

    public static final String CONSUMER = "view-aggregator";
    /** Video ids whose count changed and must be copied to the search index (see SearchStatsSync). */
    public static final String SEARCH_DIRTY_KEY = "search:dirty-view-counts";
    private static final byte[] ONE = "1".getBytes(StandardCharsets.UTF_8);

    private final StatsRepository stats;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    private final ViewsProperties properties;
    private final AppMetrics metrics;
    private final JsonMapper json;

    public ViewAggregator(StatsRepository stats, StringRedisTemplate redis, TransactionTemplate tx,
                          ViewsProperties properties, AppMetrics metrics, JsonMapper json) {
        this.stats = stats;
        this.redis = redis;
        this.tx = tx;
        this.properties = properties;
        this.metrics = metrics;
        this.json = json;
    }

    /** Continue exactly where the last committed batch ended, whatever Kafka's own committed offset says. */
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        Map<Integer, Long> stored = stats.loadOffsets(CONSUMER, Topics.VIDEO_VIEWS);
        for (TopicPartition partition : assignments.keySet()) {
            Long next = stored.get(partition.partition());
            if (next != null) {
                callback.seek(partition.topic(), partition.partition(), next);
            }
        }
    }

    @KafkaListener(id = CONSUMER, topics = Topics.VIDEO_VIEWS, containerFactory = "viewsListenerContainerFactory",
            properties = {
                    "max.poll.records=${youtube.views.max-batch}",
                    // Wait for a fuller batch: up to 1 MB or batch-wait-ms, whichever comes first.
                    "fetch.min.bytes=1048576",
                    "fetch.max.wait.ms=${youtube.views.batch-wait-ms}"})
    public void onViews(List<ConsumerRecord<String, String>> records) {
        Map<Integer, Long> nextOffsets = new HashMap<>();
        List<ViewEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            nextOffsets.merge(record.partition(), record.offset() + 1, Math::max);
            try {
                events.add(json.readValue(record.value(), ViewEvent.class));
            } catch (JacksonException e) {
                log.warn("Unreadable view event skipped at {}-{}@{}", record.topic(), record.partition(),
                        record.offset());
            }
        }

        Set<String> seen = alreadySeen(ViewBatch.keysToCheck(events));
        ViewBatch.Result result = ViewBatch.count(events, seen::contains);

        tx.executeWithoutResult(status -> {
            stats.addViews(result.counts());
            stats.saveOffsets(CONSUMER, Topics.VIDEO_VIEWS, nextOffsets);
        });
        remember(result);

        metrics.views("counted", result.counted());
        metrics.views("duplicate_event", result.duplicateEvents());
        metrics.views("repeat_viewer", result.repeatViewers());
        if (result.counted() > 0) {
            log.debug("Counted {} views for {} videos ({} duplicates, {} repeat viewers)", result.counted(),
                    result.counts().size(), result.duplicateEvents(), result.repeatViewers());
        }
    }

    /** One round trip for the whole batch. */
    private Set<String> alreadySeen(List<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            if (values != null && values.get(i) != null) {
                seen.add(keys.get(i));
            }
        }
        return seen;
    }

    /** Pipelined: one round trip however many keys. */
    private void remember(ViewBatch.Result result) {
        if (result.eventKeys().isEmpty()) {
            return;
        }
        Expiration eventTtl = Expiration.from(properties.eventDedupTtl());
        Expiration viewerTtl = Expiration.from(properties.repeatViewerWindow());
        redis.executePipelined((RedisCallback<Object>) connection -> {
            setAll(connection, result.eventKeys(), eventTtl);
            setAll(connection, result.viewerKeys(), viewerTtl);
            byte[][] videoIds = result.counts().keySet().stream()
                    .map(id -> String.valueOf(id).getBytes(StandardCharsets.UTF_8))
                    .toArray(byte[][]::new);
            if (videoIds.length > 0) {
                connection.setCommands().sAdd(bytes(SEARCH_DIRTY_KEY), videoIds);
            }
            return null;
        });
    }

    private static void setAll(RedisConnection connection, List<String> keys, Expiration ttl) {
        for (String key : keys) {
            connection.stringCommands().set(bytes(key), ONE, ttl, SetOption.upsert());
        }
    }

    private static byte[] bytes(String value) {
        return Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
    }
}
