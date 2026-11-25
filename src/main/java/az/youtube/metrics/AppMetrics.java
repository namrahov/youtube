package az.youtube.metrics;

import az.youtube.entity.Resolution;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * The application's own metrics (PDF section 9: "monitor upload failures, processing lag, startup latency, and
 * playback rebuffering"). Exposed at /actuator/prometheus with the prefix {@code youtube_}. Kafka consumer lag per
 * worker pool comes from Spring Boot's Kafka client metrics ({@code kafka_consumer_fetch_manager_records_lag_max}).
 */
@Component
public class AppMetrics {

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome initiated, completed, aborted, expired or rejected (stored object did not match) */
    public void upload(String outcome) {
        Counter.builder("youtube.uploads").description("Upload lifecycle events").tag("outcome", outcome)
                .register(registry).increment();
    }

    public void transcode(Resolution resolution, boolean succeeded, Duration duration) {
        Timer.builder("youtube.transcode.duration").description("Time to encode and store one rendition")
                .tag("resolution", resolution.label()).tag("outcome", succeeded ? "success" : "failure")
                .register(registry).record(duration);
    }

    /** A rendition that ran out of retries. */
    public void variantFailed(Resolution resolution) {
        Counter.builder("youtube.variants.failed").tag("resolution", resolution.label())
                .register(registry).increment();
    }

    /** Processing lag as a viewer feels it: from a verified upload to the first playable rendition. */
    public void timeToPlayable(Duration duration) {
        Timer.builder("youtube.video.time.to.playable").register(registry).record(duration);
    }

    /** @param outcome counted, duplicate_event, repeat_viewer */
    public void views(String outcome, long count) {
        Counter.builder("youtube.views").tag("outcome", outcome).register(registry).increment(count);
    }

    /** Time from pressing play to the first frame, reported by players. PDF target: p95 under 2 s. */
    public void playbackStartup(Duration duration) {
        Timer.builder("youtube.playback.startup").description("Time to first frame, reported by players")
                .register(registry).record(duration);
    }

    /** Share of a playback session spent stalled (0 = never rebuffered). */
    public void playbackRebufferRatio(double ratio) {
        DistributionSummary.builder("youtube.playback.rebuffer.ratio")
                .description("Stalled time / watched time per playback session")
                .register(registry).record(ratio);
    }

    public void playbackStalls(int stalls) {
        Counter.builder("youtube.playback.stalls").register(registry).increment(stalls);
    }

    public void searchQuery(Duration duration, boolean succeeded) {
        Timer.builder("youtube.search.duration").tag("outcome", succeeded ? "success" : "failure")
                .register(registry).record(duration);
    }
}
