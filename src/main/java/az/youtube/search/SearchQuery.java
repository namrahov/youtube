package az.youtube.search;

import java.util.List;
import java.util.Map;

/**
 * Relevance in two parts, multiplied:
 * <pre>
 * score = text match × (log2(2 + views) + 2 × freshness)
 * </pre>
 * <ul>
 *   <li><b>Text match</b> (BM25): the words in the title, typos allowed ({@code kafak} finds {@code kafka}); or the
 *       words as prefixes ({@code tutor} finds {@code tutorial}, search-as-you-type). A rare word scores more than a
 *       common one, and a match in a short title more than in a long one. Matching all words ({@code and}) keeps
 *       "spring kafka" from returning every video about spring.</li>
 *   <li><b>Popularity</b>: logarithmic, so 2 million views is not twice 1 million; a new video with no views still
 *       gets 1 (not 0, which would erase its text score).</li>
 *   <li><b>Freshness</b>: 1 for the first day, half after a month, so new videos can surface; it is added, not
 *       multiplied, so an old popular video does not drop to zero.</li>
 * </ul>
 * Real YouTube adds a second stage (an ML model re-ranking the top few hundred with watch time, personalisation and
 * semantic embeddings); this is the first stage.
 */
final class SearchQuery {

    private SearchQuery() {
    }

    /** Elasticsearch request body. Built from maps, never by string concatenation: the query text is user input. */
    static Map<String, Object> build(String text, int from, int size) {
        Map<String, Object> textMatch = Map.of("bool", Map.of(
                "should", List.of(
                        Map.of("match", Map.of("title", Map.of(
                                "query", text, "fuzziness", "AUTO", "operator", "and", "boost", 3))),
                        Map.of("match", Map.of("title.prefix", Map.of(
                                "query", text, "operator", "and")))),
                "minimum_should_match", 1));
        return Map.of(
                "from", from,
                "size", size,
                "track_total_hits", true,
                "_source", List.of("title", "viewCount", "publishedAt", "durationMs"),
                "query", Map.of("function_score", Map.of(
                        "query", textMatch,
                        "functions", List.of(
                                Map.of("field_value_factor", Map.of(
                                        "field", "viewCount", "modifier", "log2p", "missing", 0)),
                                Map.of("gauss", Map.of("publishedAt", Map.of(
                                        "origin", "now", "offset", "1d", "scale", "30d", "decay", 0.5)),
                                        "weight", 2)),
                        "score_mode", "sum",
                        "boost_mode", "multiply")));
    }
}
