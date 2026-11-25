package az.youtube.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("youtube.views")
public record ViewsProperties(
        Duration minWatch,
        Duration repeatViewerWindow,
        Duration eventDedupTtl,
        int batchWaitMs,
        int maxBatch) {

    /** The whole video counts as long enough when it is shorter than {@code min-watch}. */
    public Duration requiredWatch(Long videoDurationMs) {
        if (videoDurationMs == null || videoDurationMs <= 0) {
            return minWatch;
        }
        Duration whole = Duration.ofMillis(videoDurationMs).withNanos(0);
        return whole.compareTo(minWatch) < 0 ? whole : minWatch;
    }
}
