package az.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.youtube.dto.request.InitiateUploadRequest;
import az.youtube.dto.request.ViewRequest;
import az.youtube.dto.response.InitiateUploadResponse;
import az.youtube.dto.response.PartUrlsResponse;
import az.youtube.dto.response.SearchResponse;
import az.youtube.dto.response.VideoResponse;
import az.youtube.entity.VideoStatus;
import az.youtube.security.HeaderCurrentUserProvider;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** View counting, search and the storage lifecycle against real Redis, Elasticsearch, Kafka, PostgreSQL and MinIO. */
class ViewsSearchLifecycleIT extends IntegrationTest {

    private static final AtomicLong USERS = new AtomicLong(9000);

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectStorage storage;

    private RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port + "/api");
    }

    @Test
    void retriedAndRepeatedViewsAreCountedOnce() {
        long owner = USERS.incrementAndGet();
        long videoId = publishedVideo(owner, "views " + uniqueWord());
        long alice = USERS.incrementAndGet();
        long bob = USERS.incrementAndGet();
        UUID aliceView = UUID.randomUUID();

        view(alice, videoId, aliceView, 31);
        view(alice, videoId, aliceView, 31);           // retry of the same view
        view(alice, videoId, UUID.randomUUID(), 45);   // alice refreshed and watched again
        view(bob, videoId, UUID.randomUUID(), 30);
        assertThatThrownBy(() -> view(bob, videoId, UUID.randomUUID(), 5))  // not watched long enough
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        await("2 views counted", () -> get(owner, videoId).viewCount() == 2);
        // Offsets are stored with the counters, so a redelivered batch is skipped.
        assertThat(jdbc.sql("SELECT count(*) FROM consumer_offsets WHERE consumer = 'view-aggregator'")
                .query(Long.class).single()).isPositive();
        assertThat(prometheus()).contains("youtube_views_total{application=\"youtube\",outcome=\"counted\"}");
    }

    @Test
    void searchFindsPublishedVideosWithTyposAndPrefixesAndRanksByViews() {
        String word = uniqueWord();
        long owner = USERS.incrementAndGet();
        long quiet = publishedVideo(owner, word + " kafka streams tutorial");
        long popular = publishedVideo(owner, word + " kafka streams tutorial");
        publishedVideo(owner, word + " spring boot basics");

        await("both indexed", () -> search(word + " kafka").total() == 2);
        assertThat(ids(search(word + " kafak"))).containsExactlyInAnyOrder(quiet, popular);   // typo
        assertThat(ids(search(word + " tutor"))).containsExactlyInAnyOrder(quiet, popular);   // prefix
        assertThat(ids(search(word + " spring"))).hasSize(1);

        view(USERS.incrementAndGet(), popular, UUID.randomUUID(), 40);
        view(USERS.incrementAndGet(), popular, UUID.randomUUID(), 40);
        await("view count copied to the index", () -> search(word + " kafka").results().stream()
                .anyMatch(r -> r.id() == popular && r.viewCount() == 2));
        // Same title, same age: the more watched video ranks first.
        assertThat(ids(search(word + " kafka"))).containsExactly(popular, quiet);
    }

    @Test
    void deletedVideoDisappearsAndItsFilesAreRemoved() throws Exception {
        String word = uniqueWord();
        long owner = USERS.incrementAndGet();
        long videoId = publishedVideo(owner, word + " to be deleted");
        await("indexed", () -> search(word).total() == 1);
        assertThat(storage.objectSize(StorageKeys.master(videoId))).isPresent();

        delete(owner, videoId);
        delete(owner, videoId); // safe to repeat

        assertThatThrownBy(() -> get(owner, videoId))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        await("removed from search", () -> search(word).total() == 0);
        await("files purged", () -> jdbc.sql("SELECT storage_purged_at IS NOT NULL FROM videos WHERE id = :id")
                .param("id", videoId).query(Boolean.class).single());
        assertThat(storage.objectSize(StorageKeys.master(videoId))).isEmpty();
        assertThat(storage.objectSize(StorageKeys.original(videoId))).isEmpty();
        assertThat(storage.deletePrefix(StorageKeys.STREAMS_PREFIX + videoId + "/")).isZero();
    }

    @Test
    void monitoringExposesUploadProcessingAndPlaybackMetrics() throws Exception {
        long owner = USERS.incrementAndGet();
        long videoId = publishedVideo(owner, "metrics " + uniqueWord());
        HttpResponse<String> report = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/playback/reports"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"videoId\":" + videoId + ",\"startupMs\":850,\"watchedMs\":60000,\"stalledMs\":1200,\"stalls\":2}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(report.statusCode()).isEqualTo(204);

        String metrics = prometheus();
        assertThat(metrics)
                .contains("youtube_uploads_total{application=\"youtube\",outcome=\"completed\"}")
                .contains("youtube_transcode_duration_seconds_count{application=\"youtube\",outcome=\"success\",resolution=\"360p\"}")
                .contains("youtube_video_time_to_playable_seconds_count")
                .contains("youtube_variants_open{application=\"youtube\",resolution=\"4K\"}")
                .contains("youtube_outbox_oldest_age_seconds")
                .contains("youtube_playback_startup_seconds_bucket{application=\"youtube\",le=\"2.0\"}")
                .contains("youtube_playback_rebuffer_ratio_count")
                .contains("kafka_consumer_fetch_manager_records_lag_max");
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    /** Uploads random bytes (simulated engine) and waits until the video is published and indexed. */
    private long publishedVideo(long owner, String title) {
        byte[] file = new byte[1024 * 1024];
        ThreadLocalRandom.current().nextBytes(file);
        InitiateUploadResponse init = api.post().uri("/videos/uploads")
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(owner))
                .body(new InitiateUploadRequest(title, "video/mp4", file.length))
                .retrieve().body(InitiateUploadResponse.class);
        PartUrlsResponse urls = api.get().uri("/videos/{id}/upload/part-urls?from=1&count=1", init.videoId())
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(owner))
                .retrieve().body(PartUrlsResponse.class);
        try {
            HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(urls.parts().getFirst().url()))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(file)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).isEqualTo(200);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        api.post().uri("/videos/{id}/upload/complete", init.videoId())
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(owner))
                .retrieve().toBodilessEntity();
        await("video " + init.videoId() + " fully processed",
                () -> get(owner, init.videoId()).status() == VideoStatus.FULLY_PROCESSED);
        return init.videoId();
    }

    private void view(long user, long videoId, UUID eventId, int watchedSeconds) {
        api.post().uri("/videos/{id}/views", videoId)
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .body(new ViewRequest(eventId, watchedSeconds))
                .retrieve().toBodilessEntity();
    }

    private VideoResponse get(long user, long videoId) {
        return api.get().uri("/videos/{id}", videoId)
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().body(VideoResponse.class);
    }

    private void delete(long user, long videoId) {
        api.delete().uri("/videos/{id}", videoId)
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().toBodilessEntity();
    }

    private SearchResponse search(String query) {
        return api.get().uri(b -> b.path("/search").queryParam("q", query).build())
                .retrieve().body(SearchResponse.class);
    }

    private static List<Long> ids(SearchResponse response) {
        return response.results().stream().map(SearchResponse.Result::id).toList();
    }

    private String prometheus() {
        return RestClient.create("http://localhost:" + port).get().uri("/actuator/prometheus")
                .retrieve().body(String.class);
    }

    /** A made-up word, so each test finds only its own videos in the shared index. */
    private static String uniqueWord() {
        StringBuilder word = new StringBuilder("zq");
        for (int i = 0; i < 8; i++) {
            word.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return word.toString();
    }
}
