package az.youtube.config;

import az.youtube.entity.Resolution;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The per-resolution worker concurrency is read straight from the properties by the {@code @KafkaListener}s. */
@ConfigurationProperties("youtube.transcoding")
public record TranscodingProperties(
        List<Resolution> resolutions,
        int maxRetries,
        Duration retryInterval,
        Map<Resolution, Duration> simulatedDuration) {

    public Duration simulatedDurationOf(Resolution resolution) {
        return simulatedDuration.getOrDefault(resolution, Duration.ZERO);
    }
}
