package az.youtube.search;

import az.youtube.config.SearchProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The video search index, over Elasticsearch's REST API (OpenSearch accepts the same requests). The index is a copy
 * of PostgreSQL, never the source of truth: it can always be rebuilt from the database.
 * <p>
 * The application reads and writes through an alias ({@code videos}), never the index name ({@code videos-v1}).
 * A mapping change is a new index ({@code videos-v2}) filled from the database, then the alias is switched in one
 * atomic request: no downtime, and the old index stays as a rollback.
 */
@Component
public class SearchIndex implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    /**
     * Two analyzers:
     * <ul>
     *   <li>{@code folded}: words, lowercase, accents folded ({@code Ə} → {@code e}, {@code ç} → {@code c}), so a
     *       query typed without Azerbaijani letters still matches;</li>
     *   <li>{@code autocomplete}: additionally every word's prefixes of 2–15 letters, for search-as-you-type. Only at
     *       index time: the query itself is not cut into prefixes.</li>
     * </ul>
     * One shard and no replica fit a single local node. In production: shards sized to tens of GB each, and replicas
     * for read throughput and failover (search traffic is far heavier than indexing).
     */
    private static final String INDEX_DEFINITION = """
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0,
                "analysis": {
                  "filter": {
                    "prefixes": {"type": "edge_ngram", "min_gram": 2, "max_gram": 15}
                  },
                  "analyzer": {
                    "folded": {"type": "custom", "tokenizer": "standard", "filter": ["lowercase", "asciifolding"]},
                    "autocomplete": {"type": "custom", "tokenizer": "standard",
                                     "filter": ["lowercase", "asciifolding", "prefixes"]}
                  }
                }
              },
              "mappings": {
                "dynamic": "strict",
                "properties": {
                  "title": {
                    "type": "text", "analyzer": "folded",
                    "fields": {"prefix": {"type": "text", "analyzer": "autocomplete", "search_analyzer": "folded"}}
                  },
                  "ownerId": {"type": "long"},
                  "viewCount": {"type": "long"},
                  "publishedAt": {"type": "date"},
                  "durationMs": {"type": "long"}
                }
              },
              "aliases": {"%s": {}}
            }
            """;

    /** The document of one video. */
    public record VideoDocument(String title, long ownerId, long viewCount, Instant publishedAt, Long durationMs) {
    }

    public record Hit(long id, String title, long viewCount, Instant publishedAt, Long durationMs, double score) {
    }

    public record Hits(long total, List<Hit> hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchBody(HitsBody hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HitsBody(Total total, List<HitBody> hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Total(long value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HitBody(@JsonProperty("_id") String id, @JsonProperty("_score") Double score,
                   @JsonProperty("_source") HitSource source) {
    }

    /** Only the fields the query asks for ({@code _source} filter); a missing one is null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HitSource(String title, Long viewCount, Instant publishedAt, Long durationMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BulkBody(boolean errors, List<Map<String, BulkItem>> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BulkItem(@JsonProperty("_id") String id, int status, Map<String, Object> error) {
    }

    private record Response(int status, String body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final RestClient http;
    private final JsonMapper json;
    private final String alias;
    private volatile boolean ready;

    public SearchIndex(SearchProperties properties, JsonMapper json) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build());
        factory.setReadTimeout(properties.timeout());
        this.http = RestClient.builder().baseUrl(properties.url().toString()).requestFactory(factory).build();
        this.json = json;
        this.alias = properties.index();
    }

    /** Creating the index is a local convenience; the application starts even when the search engine is down. */
    @Override
    public void afterPropertiesSet() {
        try {
            ensureIndex();
        } catch (RuntimeException e) {
            log.warn("Search index not ready yet ({}); will retry on first use", e.getMessage());
        }
    }

    public void ensureIndex() {
        if (ready) {
            return;
        }
        if (!call(HttpMethod.HEAD, "/" + alias, null).ok()) {
            Response created = call(HttpMethod.PUT, "/" + alias + "-v1", INDEX_DEFINITION.formatted(alias));
            // Two instances starting together: the second finds it already created.
            if (!created.ok() && !created.body().contains("resource_already_exists_exception")) {
                throw new SearchUnavailableException("Cannot create index: " + created.body());
            }
            log.info("Search index {}-v1 created behind alias {}", alias, alias);
        }
        ready = true;
    }

    /** Creates or replaces the document. */
    public void upsert(long videoId, VideoDocument document) {
        ensureIndex();
        Response response = call(HttpMethod.PUT, "/" + alias + "/_doc/" + videoId, json.writeValueAsString(document));
        if (!response.ok()) {
            throw new SearchUnavailableException("Indexing video " + videoId + " failed: " + response.body());
        }
    }

    /** Safe to repeat: a document that is not there is already deleted. */
    public void delete(long videoId) {
        ensureIndex();
        Response response = call(HttpMethod.DELETE, "/" + alias + "/_doc/" + videoId, null);
        if (!response.ok() && response.status() != 404) {
            throw new SearchUnavailableException("Removing video " + videoId + " failed: " + response.body());
        }
    }

    /**
     * Partial updates of many documents in one bulk request. A video not in the index (not published yet, or
     * deleted) is skipped: its count is written when it is indexed.
     */
    public void updateViewCounts(Map<Long, Long> counts) {
        if (counts.isEmpty()) {
            return;
        }
        ensureIndex();
        StringBuilder ndjson = new StringBuilder();
        counts.forEach((id, views) -> ndjson
                .append(json.writeValueAsString(Map.of("update", Map.of("_index", alias, "_id", String.valueOf(id)))))
                .append('\n')
                .append(json.writeValueAsString(Map.of("doc", Map.of("viewCount", views))))
                .append('\n'));
        Response response = call(HttpMethod.POST, "/_bulk", ndjson.toString());
        if (!response.ok()) {
            throw new SearchUnavailableException("Bulk update failed: " + response.body());
        }
        BulkBody body = json.readValue(response.body(), BulkBody.class);
        if (body.errors()) {
            long failed = body.items().stream()
                    .flatMap(item -> item.values().stream())
                    .filter(item -> item.status() >= 300 && item.status() != 404)
                    .count();
            if (failed > 0) {
                throw new SearchUnavailableException(failed + " view count updates failed");
            }
        }
    }

    public Hits search(String text, int from, int size) {
        ensureIndex();
        Response response = call(HttpMethod.POST, "/" + alias + "/_search",
                json.writeValueAsString(SearchQuery.build(text, from, size)));
        if (!response.ok()) {
            throw new SearchUnavailableException("Search failed: " + response.body());
        }
        SearchBody body = json.readValue(response.body(), SearchBody.class);
        List<Hit> hits = body.hits().hits().stream()
                .map(h -> new Hit(Long.parseLong(h.id()), h.source().title(),
                        h.source().viewCount() == null ? 0 : h.source().viewCount(),
                        h.source().publishedAt(), h.source().durationMs(), h.score() == null ? 0 : h.score()))
                .toList();
        return new Hits(body.hits().total().value(), hits);
    }

    /** Makes the latest writes searchable now instead of within the next second (tests only). */
    public void refresh() {
        call(HttpMethod.POST, "/" + alias + "/_refresh", null);
    }

    /** Status and body for every answer, so callers decide which statuses are fine (404 on delete is). */
    private Response call(HttpMethod method, String path, String body) {
        try {
            RestClient.RequestBodySpec request = http.method(method).uri(path);
            if (body != null) {
                MediaType type = path.endsWith("/_bulk") ? MediaType.valueOf("application/x-ndjson")
                        : MediaType.APPLICATION_JSON;
                request.contentType(type).body(body);
            }
            return request.exchange((req, res) -> new Response(res.getStatusCode().value(),
                    StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8)));
        } catch (UncheckedIOException | RestClientException e) {
            throw new SearchUnavailableException("Search engine unreachable: " + e.getMessage(), e);
        }
    }
}
