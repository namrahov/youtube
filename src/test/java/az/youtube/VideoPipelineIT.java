package az.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import az.youtube.dto.request.InitiateUploadRequest;
import az.youtube.dto.response.InitiateUploadResponse;
import az.youtube.dto.response.PartUrlsResponse;
import az.youtube.dto.response.UploadProgressResponse;
import az.youtube.dto.response.VideoResponse;
import az.youtube.entity.Resolution;
import az.youtube.entity.VariantStatus;
import az.youtube.entity.VideoStatus;
import az.youtube.security.HeaderCurrentUserProvider;
import az.youtube.service.ProcessingService;
import az.youtube.service.UploadService;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.model.S3Exception;

class VideoPipelineIT extends IntegrationTest {

    private static final int MIB = 1024 * 1024;
    private static final AtomicLong USERS = new AtomicLong(1000);

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ProcessingService processing;

    @Autowired
    private UploadService uploads;

    @Autowired
    private ObjectStorage storage;

    private RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port + "/api/videos");
    }

    @Test
    void uploadedVideoGoesThroughEveryResolutionToFullyProcessed() throws Exception {
        long user = USERS.incrementAndGet();
        byte[] file = randomBytes(11 * MIB);

        InitiateUploadResponse init = initiate(user, file.length);
        assertThat(init.status()).isEqualTo(VideoStatus.UPLOADING);
        assertThat(init.partCount()).isEqualTo(3);

        uploadParts(user, init, file, 1, 3);
        VideoResponse completed = complete(user, init.videoId());
        assertThat(completed.status()).isNotEqualTo(VideoStatus.UPLOADING);

        VideoResponse done = awaitStatus(user, init.videoId(), VideoStatus.FULLY_PROCESSED);
        assertThat(done.variants()).extracting(VideoResponse.Variant::status).containsOnly(VariantStatus.COMPLETED);
        assertThat(done.variants()).extracting(VideoResponse.Variant::resolution)
                .containsExactly("360p", "720p", "1080p", "4K");
        assertThat(done.manifestKey()).isEqualTo(StorageKeys.master(init.videoId()));
        assertThat(storage.getText(done.manifestKey()))
                .contains("360p/index.m3u8", "720p/index.m3u8", "1080p/index.m3u8", "4k/index.m3u8");

        assertThat(outboxEvents(init.videoId())).containsEntry("VideoUploaded", 1L).containsEntry("TranscodeRequested", 4L);
    }

    @Test
    void failedFourKDoesNotBlockPlaybackAndDuplicateEventsChangeNothing() throws Exception {
        long user = USERS.incrementAndGet();
        byte[] file = randomBytes(11 * MIB);
        InitiateUploadResponse init = initiate(user, file.length);
        long videoId = init.videoId();
        transcoder.failAlways(videoId, Resolution.P2160);

        uploadParts(user, init, file, 1, 3);
        complete(user, videoId);

        await("all variants final", () -> {
            var variants = get(user, videoId).variants();
            // Empty until the planner has run: allMatch alone would be true at once.
            return variants.size() == Resolution.values().length && variants.stream()
                    .allMatch(v -> v.status() == VariantStatus.COMPLETED || v.status() == VariantStatus.FAILED);
        });
        VideoResponse video = get(user, videoId);
        assertThat(video.status()).isEqualTo(VideoStatus.PLAYABLE);
        assertThat(video.variants()).extracting(VideoResponse.Variant::resolution, VideoResponse.Variant::status)
                .containsExactly(
                        tuple("360p", VariantStatus.COMPLETED),
                        tuple("720p", VariantStatus.COMPLETED),
                        tuple("1080p", VariantStatus.COMPLETED),
                        tuple("4K", VariantStatus.FAILED));
        assertThat(storage.getText(video.manifestKey())).contains("1080p/index.m3u8").doesNotContain("4k/");

        // Retried once (max-retries=1), then FAILED: 2 attempts in total.
        assertThat(jdbc.sql("SELECT attempts FROM video_variants WHERE video_id = :id AND resolution = '4K'")
                .param("id", videoId).query(Integer.class).single()).isEqualTo(2);

        // A redelivered "720p completed" must not count as the fourth completion.
        processing.completeVariant(videoId, Resolution.P720, StorageKeys.renditionPlaylist(videoId, Resolution.P720));
        // A redelivered 4K job is skipped: the variant is final.
        assertThat(processing.claimVariant(videoId, Resolution.P2160)).isFalse();
        assertThat(get(user, videoId).status()).isEqualTo(VideoStatus.PLAYABLE);
    }

    @Test
    void completingTwiceWritesOneEvent() throws Exception {
        long user = USERS.incrementAndGet();
        byte[] file = randomBytes(6 * MIB);
        InitiateUploadResponse init = initiate(user, file.length);
        uploadParts(user, init, file, 1, init.partCount());

        complete(user, init.videoId());
        VideoResponse again = complete(user, init.videoId());

        assertThat(again.status()).isNotEqualTo(VideoStatus.UPLOADING);
        assertThat(outboxEvents(init.videoId())).containsEntry("VideoUploaded", 1L);
    }

    @Test
    void missingPartBlocksCompletionUntilTheClientResumes() throws Exception {
        long user = USERS.incrementAndGet();
        byte[] file = randomBytes(11 * MIB);
        InitiateUploadResponse init = initiate(user, file.length);
        uploadParts(user, init, file, 1, 2);

        UploadProgressResponse progress = api.get().uri("/{id}/upload/progress", init.videoId())
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().body(UploadProgressResponse.class);
        assertThat(progress.uploadedParts()).containsExactly(1, 2);
        assertThat(progress.missingParts()).containsExactly(3);

        assertThatThrownBy(() -> complete(user, init.videoId()))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        uploadParts(user, init, file, 3, 3);
        assertThat(complete(user, init.videoId()).status()).isNotEqualTo(VideoStatus.UPLOADING);
    }

    @Test
    void uploadInProgressIsInvisibleToOtherUsers() {
        long owner = USERS.incrementAndGet();
        InitiateUploadResponse init = initiate(owner, 6 * MIB);

        assertThatThrownBy(() -> get(owner + 1_000_000, init.videoId()))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(get(owner, init.videoId()).status()).isEqualTo(VideoStatus.UPLOADING);
    }

    @Test
    void abandonedUploadExpiresAndItsPartsAreDeleted() throws Exception {
        long user = USERS.incrementAndGet();
        byte[] file = randomBytes(6 * MIB);
        InitiateUploadResponse init = initiate(user, file.length);
        uploadParts(user, init, file, 1, 1);
        String uploadId = jdbc.sql("SELECT s3_upload_id FROM videos WHERE id = :id")
                .param("id", init.videoId()).query(String.class).single();

        jdbc.sql("UPDATE videos SET upload_expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", init.videoId()).update();
        uploads.expireAbandoned(100);

        assertThat(get(user, init.videoId()).status()).isEqualTo(VideoStatus.UPLOAD_FAILED);
        assertThatThrownBy(() -> storage.listParts(StorageKeys.original(init.videoId()), uploadId))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(404));
    }

    private InitiateUploadResponse initiate(long user, long size) {
        return api.post().uri("/uploads")
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .body(new InitiateUploadRequest("Test video", "video/mp4", size))
                .retrieve().body(InitiateUploadResponse.class);
    }

    /** Uploads parts {@code first..last} straight to S3 with presigned URLs, as a browser would. */
    private void uploadParts(long user, InitiateUploadResponse init, byte[] file, int first, int last)
            throws IOException, InterruptedException {
        PartUrlsResponse urls = api.get().uri("/{id}/upload/part-urls?from={from}&count={count}",
                        init.videoId(), first, last - first + 1)
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().body(PartUrlsResponse.class);
        for (PartUrlsResponse.PartUrl part : urls.parts()) {
            int from = (int) ((part.partNumber() - 1) * init.partSizeBytes());
            int to = (int) Math.min(file.length, from + init.partSizeBytes());
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(part.url()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(Arrays.copyOfRange(file, from, to)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
    }

    private VideoResponse complete(long user, long videoId) {
        return api.post().uri("/{id}/upload/complete", videoId)
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().body(VideoResponse.class);
    }

    private VideoResponse get(long user, long videoId) {
        return api.get().uri("/{id}", videoId)
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().body(VideoResponse.class);
    }

    private VideoResponse awaitStatus(long user, long videoId, VideoStatus status) {
        await("video " + videoId + " " + status, () -> get(user, videoId).status() == status);
        return get(user, videoId);
    }

    private Map<String, Long> outboxEvents(long videoId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id")
                .param("id", videoId)
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
