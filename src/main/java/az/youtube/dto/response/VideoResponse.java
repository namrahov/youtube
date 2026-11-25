package az.youtube.dto.response;

import az.youtube.entity.VariantStatus;
import az.youtube.entity.VideoStatus;

import java.time.Instant;
import java.util.List;

/**
 * @param manifestKey object key of the HLS master playlist once the video is PLAYABLE
 * @param playbackUrl the master playlist's URL for a player (hls.js, Safari, ExoPlayer, AVPlayer) once the video is
 *                    PLAYABLE: through the CDN in production. The player then picks renditions and segments itself
 */
public record VideoResponse(
        long id,
        String title,
        VideoStatus status,
        long sizeBytes,
        String manifestKey,
        String playbackUrl,
        List<Variant> variants,
        Instant createdAt) {

    /** @param width {@code null} until the variant is COMPLETED */
    public record Variant(String resolution, VariantStatus status, Integer width, Integer height) {
    }
}
