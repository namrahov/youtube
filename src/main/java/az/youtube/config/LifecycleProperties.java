package az.youtube.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param failedOriginalRetention how long the original of a video that failed processing is kept */
@ConfigurationProperties("youtube.lifecycle")
public record LifecycleProperties(Duration failedOriginalRetention) {
}
