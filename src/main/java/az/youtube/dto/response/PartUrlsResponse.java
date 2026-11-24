package az.youtube.dto.response;

import java.time.Instant;
import java.util.List;

/** The client PUTs each part's bytes to its URL and keeps nothing else: the server lists the parts on complete. */
public record PartUrlsResponse(List<PartUrl> parts, Instant expiresAt) {

    public record PartUrl(int partNumber, String url) {
    }
}
