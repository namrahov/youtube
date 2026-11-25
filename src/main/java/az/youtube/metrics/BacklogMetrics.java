package az.youtube.metrics;

import az.youtube.entity.Resolution;
import az.youtube.repository.OutboxRepository;
import az.youtube.repository.VariantRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backlog gauges, the signals a processing spike shows up in first and that autoscaling would act on:
 * <ul>
 *   <li>{@code youtube_variants_open{resolution}}: jobs PENDING or PROCESSING per worker pool;</li>
 *   <li>{@code youtube_outbox_pending} and {@code youtube_outbox_oldest_age_seconds}: events not yet in Kafka.
 *       A growing age means Kafka is down or the publisher is stuck.</li>
 * </ul>
 * Refreshed on a schedule from small partial indexes, not on every scrape: a scrape must not run SQL.
 */
@Component
public class BacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

    private final OutboxRepository outbox;
    private final VariantRepository variants;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();
    private final Map<Resolution, AtomicLong> openJobs = new EnumMap<>(Resolution.class);

    public BacklogMetrics(OutboxRepository outbox, VariantRepository variants, MeterRegistry registry) {
        this.outbox = outbox;
        this.variants = variants;
        Gauge.builder("youtube.outbox.pending", outboxPending, AtomicLong::get).register(registry);
        Gauge.builder("youtube.outbox.oldest.age.seconds", outboxOldestAgeSeconds, AtomicLong::get).register(registry);
        for (Resolution resolution : Resolution.values()) {
            AtomicLong value = new AtomicLong();
            openJobs.put(resolution, value);
            Gauge.builder("youtube.variants.open", value, AtomicLong::get)
                    .description("Transcoding jobs waiting or running")
                    .tag("resolution", resolution.label()).register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${youtube.metrics.backlog-refresh-ms:15000}")
    public void refresh() {
        try {
            OutboxRepository.PendingStats stats = outbox.pendingStats();
            outboxPending.set(stats.count());
            outboxOldestAgeSeconds.set(stats.oldestAgeSeconds());
            Map<Resolution, Long> open = variants.openJobsByResolution();
            openJobs.forEach((resolution, value) -> value.set(open.getOrDefault(resolution, 0L)));
        } catch (RuntimeException e) {
            log.warn("Backlog metrics not refreshed: {}", e.getMessage());
        }
    }
}
