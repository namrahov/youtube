package az.youtube.dto.response;

import az.youtube.entity.VariantStatus;
import az.youtube.entity.VideoStatus;

import java.time.Instant;
import java.util.List;

/**
 * @param manifestKey object key of the HLS master playlist once the video is PLAYABLE; a CDN in front of the
 *                    bucket would serve it (the CDN is out of scope for this step)
 */
public record VideoResponse(
        long id,
        String title,
        VideoStatus status,
        long sizeBytes,
        String manifestKey,
        List<Variant> variants,
        Instant createdAt) {

    public record Variant(String resolution, VariantStatus status) {
    }
}
