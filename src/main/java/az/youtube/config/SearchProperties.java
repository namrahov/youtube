package az.youtube.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param index alias used for reads and writes; the concrete index behind it is {@code <index>-v1}
 */
@ConfigurationProperties("youtube.search")
public record SearchProperties(URI url, String index, Duration timeout) {
}
