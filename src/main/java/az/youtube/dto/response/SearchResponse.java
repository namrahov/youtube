package az.youtube.dto.response;

import java.time.Instant;
import java.util.List;

/** @param total matching videos (all pages) */
public record SearchResponse(long total, int page, int size, List<Result> results) {

    public record Result(long id, String title, long viewCount, Instant publishedAt, Long durationMs,
                         String playbackUrl) {
    }
}
