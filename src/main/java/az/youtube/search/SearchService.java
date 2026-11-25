package az.youtube.search;

import az.youtube.config.PlaybackProperties;
import az.youtube.dto.response.SearchResponse;
import az.youtube.metrics.AppMetrics;
import az.youtube.storage.StorageKeys;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** The read path: the query goes to the search engine only, never to PostgreSQL. */
@Service
public class SearchService {

    /** Deep pages are expensive for the engine (every shard sorts from + size hits); YouTube does not offer them. */
    static final int MAX_RESULT_WINDOW = 500;
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_QUERY_LENGTH = 200;

    private final SearchIndex index;
    private final PlaybackProperties playback;
    private final AppMetrics metrics;

    public SearchService(SearchIndex index, PlaybackProperties playback, AppMetrics metrics) {
        this.index = index;
        this.playback = playback;
        this.metrics = metrics;
    }

    public SearchResponse search(String query, int page, int size) {
        String text = query == null ? "" : query.strip();
        if (text.isEmpty() || text.length() > MAX_QUERY_LENGTH) {
            throw badRequest("q must be 1-" + MAX_QUERY_LENGTH + " characters");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (page + 1) * size > MAX_RESULT_WINDOW) {
            throw badRequest("page >= 0, size 1-" + MAX_PAGE_SIZE + ", at most " + MAX_RESULT_WINDOW + " results");
        }
        long started = System.nanoTime();
        SearchIndex.Hits hits;
        try {
            hits = index.search(text, page * size, size);
        } catch (SearchUnavailableException e) {
            metrics.searchQuery(Duration.ofNanos(System.nanoTime() - started), false);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Search is unavailable", e);
        }
        metrics.searchQuery(Duration.ofNanos(System.nanoTime() - started), true);
        return new SearchResponse(hits.total(), page, size, hits.hits().stream()
                .map(h -> new SearchResponse.Result(h.id(), h.title(), h.viewCount(), h.publishedAt(), h.durationMs(),
                        playback.urlOf(StorageKeys.master(h.id()))))
                .toList());
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
