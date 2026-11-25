package az.youtube.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param createBucket  local convenience: create the bucket on startup
 * @param publicStreams local convenience: allow anonymous GET under {@code streams/} so a player can fetch the
 *                      playlists and segments straight from MinIO. In production the bucket stays private and only
 *                      the CDN may read it (e.g. CloudFront origin access control)
 */
@ConfigurationProperties("youtube.storage")
public record StorageProperties(
        URI endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        boolean createBucket,
        boolean publicStreams) {
}
