package az.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import az.youtube.dto.request.InitiateUploadRequest;
import az.youtube.dto.response.InitiateUploadResponse;
import az.youtube.dto.response.PartUrlsResponse;
import az.youtube.dto.response.VideoResponse;
import az.youtube.entity.VariantStatus;
import az.youtube.entity.VideoStatus;
import az.youtube.security.HeaderCurrentUserProvider;
import az.youtube.service.VideoLifecycleService;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The real pipeline with ffmpeg (PDF section 5): a 9 s 720p test video becomes aligned fMP4 HLS renditions that an
 * anonymous client can stream. Skipped when ffmpeg is not installed.
 */
@EnabledIf("ffmpegAvailable")
class FfmpegPipelineIT extends IntegrationTest {

    private static final AtomicLong USERS = new AtomicLong(5000);
    private static final Duration ENCODE_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @TempDir
    Path tempDir;

    @Autowired
    private VideoLifecycleService lifecycle;

    @Autowired
    private ObjectStorage storage;

    private RestClient api;

    @DynamicPropertySource
    static void ffmpegEngine(DynamicPropertyRegistry registry) {
        registry.add("youtube.transcoding.engine", () -> "ffmpeg");
        registry.add("youtube.transcoding.ffmpeg.preset", () -> "ultrafast");
    }

    static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port + "/api/videos");
    }

    @Test
    void sevenTwentyPSourceBecomesAlignedHlsRenditionsWithoutUpscaling() throws Exception {
        long user = USERS.incrementAndGet();
        byte[] file = testVideo(1280, 720, 9);

        long videoId = upload(user, file);
        await("video " + videoId + " FULLY_PROCESSED", ENCODE_TIMEOUT,
                () -> get(user, videoId).status() == VideoStatus.FULLY_PROCESSED);
        VideoResponse video = get(user, videoId);

        assertThat(video.variants())
                .extracting(VideoResponse.Variant::resolution, VideoResponse.Variant::status,
                        VideoResponse.Variant::width, VideoResponse.Variant::height)
                .containsExactly(
                        tuple("360p", VariantStatus.COMPLETED, 640, 360),
                        tuple("720p", VariantStatus.COMPLETED, 1280, 720),
                        tuple("1080p", VariantStatus.SKIPPED, null, null),
                        tuple("4K", VariantStatus.SKIPPED, null, null));

        // The master playlist, fetched the way a player does: anonymously, from the playback URL.
        String master = fetch(video.playbackUrl());
        assertThat(master)
                .contains("CODECS=\"avc1.64001e,mp4a.40.2\",RESOLUTION=640x360\n360p/index.m3u8")
                .contains("CODECS=\"avc1.640020,mp4a.40.2\",RESOLUTION=1280x720\n720p/index.m3u8")
                .doesNotContain("1080p", "4k/");

        String base = video.playbackUrl().substring(0, video.playbackUrl().lastIndexOf('/') + 1);
        String p360 = fetch(base + "360p/index.m3u8");
        String p720 = fetch(base + "720p/index.m3u8");
        assertThat(p360).contains("#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-ENDLIST");
        // 4 s segments starting on the same forced keyframes: identical cut points in every rendition, so the
        // player can switch between 360p and 720p at any segment boundary.
        assertThat(segmentDurations(p360)).containsExactly("4.000000", "4.000000", "1.000000");
        assertThat(segmentDurations(p720)).isEqualTo(segmentDurations(p360));

        HttpResponse<byte[]> segment = http.send(HttpRequest.newBuilder(URI.create(base + "720p/seg_00000.m4s")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(segment.statusCode()).isEqualTo(200);
        assertThat(segment.headers().firstValue("Content-Type")).hasValue("video/iso.segment");
        assertThat(segment.body().length).isPositive();
    }

    @Test
    void fileThatIsNotAVideoIsRejectedWithoutRetries() throws Exception {
        long user = USERS.incrementAndGet();
        byte[] garbage = new byte[64 * 1024];
        ThreadLocalRandom.current().nextBytes(garbage);

        long videoId = upload(user, garbage);

        await("video " + videoId + " PROCESSING_FAILED", ENCODE_TIMEOUT,
                () -> get(user, videoId).status() == VideoStatus.PROCESSING_FAILED);
        assertThat(get(user, videoId).variants()).isEmpty();

        // Kept for the retention period (7 days by default), then the original is deleted.
        lifecycle.purgeFailed(100);
        assertThat(storage.objectSize(StorageKeys.original(videoId))).isPresent();
        jdbc.sql("UPDATE videos SET updated_at = now() - interval '8 days' WHERE id = :id").param("id", videoId)
                .update();
        lifecycle.purgeFailed(100);
        assertThat(storage.objectSize(StorageKeys.original(videoId))).isEmpty();
    }

    /** A test pattern with a 440 Hz tone, H.264 + AAC in MP4: what a phone or camera would upload. */
    private byte[] testVideo(int width, int height, int seconds) throws Exception {
        Path output = tempDir.resolve("source.mp4");
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=25:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                output.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return Files.readAllBytes(output);
    }

    private long upload(long user, byte[] file) throws Exception {
        InitiateUploadResponse init = api.post().uri("/uploads")
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .body(new InitiateUploadRequest("ffmpeg test", "video/mp4", file.length))
                .retrieve().body(InitiateUploadResponse.class);
        PartUrlsResponse urls = api.get().uri("/{id}/upload/part-urls?from=1&count={count}", init.videoId(),
                        init.partCount())
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().body(PartUrlsResponse.class);
        for (PartUrlsResponse.PartUrl part : urls.parts()) {
            int from = (int) ((part.partNumber() - 1) * init.partSizeBytes());
            int to = (int) Math.min(file.length, from + init.partSizeBytes());
            HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(part.url()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(Arrays.copyOfRange(file, from, to))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        }
        api.post().uri("/{id}/upload/complete", init.videoId())
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().toBodilessEntity();
        return init.videoId();
    }

    private VideoResponse get(long user, long videoId) {
        return api.get().uri("/{id}", videoId)
                .header(HeaderCurrentUserProvider.HEADER, String.valueOf(user))
                .retrieve().body(VideoResponse.class);
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url).isEqualTo(200);
        return response.body();
    }

    private static List<String> segmentDurations(String playlist) {
        return playlist.lines()
                .filter(l -> l.startsWith("#EXTINF:"))
                .map(l -> l.substring("#EXTINF:".length(), l.indexOf(',')))
                .toList();
    }
}
