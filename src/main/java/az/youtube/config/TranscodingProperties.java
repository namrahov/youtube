package az.youtube.config;

import az.youtube.entity.Resolution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param engine            {@code ffmpeg} encodes real HLS renditions; {@code simulated} only waits and writes empty
 *                          playlists (tests, machines without ffmpeg)
 * @param jobTimeout        longest a single rendition may encode before it is killed and retried
 * @param transferAllowance time on top of {@code jobTimeout} for downloading the original and uploading segments.
 *                          Together they are the worker pool's {@code max.poll.interval.ms}, so Kafka never hands a
 *                          job that is still running to a second worker (see KafkaConfig)
 */
@ConfigurationProperties("youtube.transcoding")
public record TranscodingProperties(
        Engine engine,
        List<Resolution> resolutions,
        int maxRetries,
        Duration retryInterval,
        Map<Resolution, Duration> jobTimeout,
        Duration transferAllowance,
        Map<Resolution, Duration> simulatedDuration,
        Ffmpeg ffmpeg) {

    public enum Engine {
        SIMULATED,
        FFMPEG
    }

    /**
     * @param segmentDuration  HLS segment length (the {@code -hls_time} target)
     * @param keyframeInterval a keyframe is forced at every multiple of this, in every rendition, so segment
     *                         boundaries line up across renditions and the player can switch quality at any segment
     * @param workDir          local scratch space for the original and the encoded files of running jobs
     */
    public record Ffmpeg(
            String ffmpegPath,
            String ffprobePath,
            String preset,
            Duration segmentDuration,
            Duration keyframeInterval,
            Duration probeTimeout,
            Path workDir) {

        public Ffmpeg {
            if (segmentDuration.toMillis() % keyframeInterval.toMillis() != 0) {
                throw new IllegalArgumentException("segment-duration must be a multiple of keyframe-interval, or "
                        + "segments would not start on a keyframe");
            }
        }
    }

    public Duration simulatedDurationOf(Resolution resolution) {
        return simulatedDuration.getOrDefault(resolution, Duration.ZERO);
    }

    public Duration jobTimeoutOf(Resolution resolution) {
        Duration timeout = jobTimeout.get(resolution);
        if (timeout == null) {
            throw new IllegalStateException("youtube.transcoding.job-timeout." + resolution.name() + " is not set");
        }
        return timeout;
    }

    /** {@code max.poll.interval.ms} of this resolution's worker pool. */
    public Duration maxPollInterval(Resolution resolution) {
        return jobTimeoutOf(resolution).plus(transferAllowance);
    }

    /** Always encoded, even from a smaller source, so every valid video gets a playable variant. */
    public Resolution lowestPlanned() {
        return resolutions.stream().min(Comparator.naturalOrder()).orElseThrow();
    }
}
