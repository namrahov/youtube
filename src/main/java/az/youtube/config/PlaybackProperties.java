package az.youtube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl where players fetch {@code streams/...} from: the CDN domain in production (e.g.
 *                {@code https://video-cdn.example.com}), MinIO's bucket URL locally
 */
@ConfigurationProperties("youtube.playback")
public record PlaybackProperties(String baseUrl) {

    public PlaybackProperties {
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String urlOf(String objectKey) {
        return baseUrl + "/" + objectKey;
    }
}
