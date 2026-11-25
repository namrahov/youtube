package az.youtube.transcoding;

import az.youtube.config.TranscodingProperties;
import az.youtube.dto.event.TranscodeRequestedEvent;
import az.youtube.entity.Resolution;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Encodes one rendition as HLS with fMP4 (CMAF) segments: {@code index.m3u8} + {@code init.mp4} +
 * {@code seg_NNNNN.m4s}. fMP4 rather than MPEG-TS so the same segments can later be announced in a DASH manifest
 * as well.
 * <p>
 * Every rendition is encoded by a different worker, at a different time, so the renditions cannot be aligned by
 * encoding them together. They are aligned by construction instead: a keyframe is forced at every multiple of
 * {@code keyframe-interval} seconds of presentation time (and scene-cut keyframes are switched off), and the segment
 * length is a multiple of that interval. So segment N of 360p and segment N of 4K start at the same instant with a
 * keyframe, and the player can switch quality between any two segments without a glitch.
 * <p>
 * The rate is capped ({@code -maxrate}/{@code -bufsize}) so no segment is far above the announced bandwidth; the
 * announced peak and average are then measured from the actual segment sizes.
 */
@Component
@ConditionalOnProperty(prefix = "youtube.transcoding", name = "engine", havingValue = "ffmpeg")
public class FfmpegTranscoder implements Transcoder, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(FfmpegTranscoder.class);

    static final String PLAYLIST = "index.m3u8";
    static final String INIT_SEGMENT = "init.mp4";
    static final String SEGMENT_PATTERN = "seg_%05d.m4s";
    /** Every rendition file is written once under a fixed key and never changed: cache it for good. */
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final ObjectStorage storage;
    private final TranscodingProperties properties;
    private final TranscodingProperties.Ffmpeg ffmpeg;

    public FfmpegTranscoder(ObjectStorage storage, TranscodingProperties properties) {
        this.storage = storage;
        this.properties = properties;
        this.ffmpeg = properties.ffmpeg();
    }

    /** Fails at startup, not at the first job, when ffmpeg or ffprobe is missing. */
    @Override
    public void afterPropertiesSet() throws IOException {
        Files.createDirectories(ffmpeg.workDir());
        for (String program : List.of(ffmpeg.ffmpegPath(), ffmpeg.ffprobePath())) {
            Path output = Files.createTempFile(ffmpeg.workDir(), "version-", ".txt");
            try {
                if (!ExternalProcess.run(List.of(program, "-version"), Duration.ofSeconds(20), output).succeeded()) {
                    throw new IllegalStateException(program + " -version failed");
                }
            } catch (TranscodingException e) {
                throw new IllegalStateException("youtube.transcoding.engine=ffmpeg, but '" + program + "' cannot be "
                        + "run. Install ffmpeg, set FFMPEG_PATH / FFPROBE_PATH, or use TRANSCODING_ENGINE=simulated",
                        e);
            } finally {
                Files.deleteIfExists(output);
            }
        }
    }

    @Override
    public Rendition transcode(TranscodeRequestedEvent job) {
        Resolution resolution = job.resolution();
        FrameSize size = FrameSize.of(resolution, job.sourceWidth(), job.sourceHeight());
        Path jobDir = ffmpeg.workDir().resolve(job.videoId() + "-" + resolution.pathSegment() + "-" + UUID.randomUUID());
        Path source = jobDir.resolve("source");
        Path out = jobDir.resolve("out");
        long started = System.nanoTime();
        try {
            Files.createDirectories(out);
            download(job.sourceS3Key(), source);

            ExternalProcess.Result result = ExternalProcess.run(command(source, resolution, size),
                    properties.jobTimeoutOf(resolution), jobDir.resolve("ffmpeg.log"), out);
            if (!result.succeeded()) {
                throw new TranscodingException("ffmpeg failed for " + resolution.label() + " (exit "
                        + result.exitCode() + "): " + result.tail(8));
            }

            List<HlsPlaylist.Segment> segments = HlsPlaylist.segments(
                    Files.readString(out.resolve(PLAYLIST), StandardCharsets.UTF_8), uri -> size(out.resolve(uri)));
            if (segments.isEmpty()) {
                throw new TranscodingException("ffmpeg produced no segments for " + resolution.label());
            }
            HlsPlaylist.Bitrate bitrate = HlsPlaylist.bitrate(segments, seconds(ffmpeg.segmentDuration()));

            String playlistKey = upload(job.videoId(), resolution, out);
            log.info("Video {}: {} encoded as {}x{}, {} segments, peak {} kbit/s, average {} kbit/s in {} s",
                    job.videoId(), resolution.label(), size.width(), size.height(), segments.size(),
                    bitrate.peak() / 1000, bitrate.average() / 1000, (System.nanoTime() - started) / 1_000_000_000);
            return new Rendition(playlistKey, size.width(), size.height(), bitrate.peak(), bitrate.average(),
                    resolution.codecs(job.hasAudio()));
        } catch (IOException e) {
            throw new TranscodingException("Local file error for " + resolution.label() + ": " + e.getMessage(), e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    /**
     * Runs in the rendition's output directory, so the playlist refers to its segments by bare file names: they are
     * uploaded next to it and resolved relative to it.
     */
    List<String> command(Path source, Resolution resolution, FrameSize size) {
        int maxKbps = resolution.maxVideoKbps();
        return List.of(
                ffmpeg.ffmpegPath(), "-hide_banner", "-nostdin", "-nostats", "-loglevel", "warning", "-y",
                "-i", source.toAbsolutePath().toString(),
                // First video stream; first audio stream if there is one ("?": no error without audio).
                "-map", "0:v:0", "-map", "0:a:0?",
                "-vf", "scale=" + size.width() + ":" + size.height() + ",setsar=1",
                "-c:v", "libx264", "-preset", ffmpeg.preset(),
                "-profile:v", "high", "-level:v", resolution.h264LevelName(), "-pix_fmt", "yuv420p",
                "-b:v", resolution.videoKbps() + "k", "-maxrate", maxKbps + "k", "-bufsize", (2 * maxKbps) + "k",
                // Keyframes at fixed presentation times, the same in every rendition; no extra ones at scene cuts.
                "-force_key_frames", "expr:gte(t,n_forced*" + decimal(ffmpeg.keyframeInterval()) + ")",
                "-sc_threshold", "0",
                "-c:a", "aac", "-b:a", Resolution.AUDIO_KBPS + "k", "-ac", "2", "-ar", "48000",
                "-f", "hls",
                "-hls_time", decimal(ffmpeg.segmentDuration()),
                "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", INIT_SEGMENT,
                "-hls_segment_filename", SEGMENT_PATTERN,
                "-hls_flags", "independent_segments",
                PLAYLIST);
    }

    private void download(String key, Path target) {
        try {
            storage.download(key, target);
        } catch (SdkException e) {
            throw new TranscodingException("Cannot download original " + key + ": " + e.getMessage(), e);
        }
    }

    /**
     * Segments and the init segment first, the playlist last: a player (or a CDN) that sees the playlist can fetch
     * every file it lists. The master playlist is updated only after this returns.
     */
    private String upload(long videoId, Resolution resolution, Path out) throws IOException {
        String prefix = StorageKeys.renditionDir(videoId, resolution);
        List<Path> files;
        try (Stream<Path> listing = Files.list(out)) {
            files = listing.filter(Files::isRegularFile).sorted().toList();
        }
        try {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.equals(PLAYLIST)) {
                    storage.putFile(prefix + name, file, contentType(name), IMMUTABLE);
                }
            }
            String playlistKey = prefix + PLAYLIST;
            storage.putFile(playlistKey, out.resolve(PLAYLIST), contentType(PLAYLIST), IMMUTABLE);
            return playlistKey;
        } catch (SdkException e) {
            throw new TranscodingException("Upload of " + resolution.label() + " failed: " + e.getMessage(), e);
        }
    }

    static String contentType(String fileName) {
        if (fileName.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (fileName.endsWith(".m4s")) {
            return "video/iso.segment";
        }
        if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    /** 2 s → "2", 1.5 s → "1.5": the form ffmpeg options expect. */
    static String decimal(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis(), 3).stripTrailingZeros().toPlainString();
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Cannot delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Cannot clean up {}: {}", dir, e.getMessage());
        }
    }
}
