package az.youtube.transcoding;

import az.youtube.config.TranscodingProperties;
import az.youtube.storage.ObjectStorage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs ffprobe against a presigned GET URL: ffprobe reads only the container headers with HTTP range requests, so the
 * planner learns the size of a 2 GB original without downloading it.
 */
@Component
@ConditionalOnProperty(prefix = "youtube.transcoding", name = "engine", havingValue = "ffmpeg")
public class FfprobeMediaProbe implements MediaProbe {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeOutput(List<Stream> streams, Format format) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Stream(@JsonProperty("codec_type") String codecType, Integer width, Integer height,
                  Map<String, Integer> disposition, @JsonProperty("side_data_list") List<SideData> sideData) {

        boolean isVideo() {
            // Cover art in an audio file is a "video" stream too; it is a single picture, not a video.
            return "video".equals(codecType) && (disposition == null || disposition.getOrDefault("attached_pic", 0) == 0);
        }

        int rotation() {
            return sideData == null ? 0 : sideData.stream()
                    .map(SideData::rotation)
                    .filter(r -> r != null)
                    .findFirst()
                    .orElse(0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SideData(Integer rotation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Format(String duration) {
    }

    private final ObjectStorage storage;
    private final TranscodingProperties.Ffmpeg properties;
    private final JsonMapper json;

    public FfprobeMediaProbe(ObjectStorage storage, TranscodingProperties properties, JsonMapper json) {
        this.storage = storage;
        this.properties = properties.ffmpeg();
        this.json = json;
    }

    @Override
    public Optional<SourceInfo> probe(String sourceKey) {
        Duration timeout = properties.probeTimeout();
        String url = storage.presignGet(sourceKey, timeout.plusMinutes(1)).toString();
        Path output = tempFile();
        try {
            ExternalProcess.Result result = ExternalProcess.run(List.of(
                    properties.ffprobePath(), "-v", "error",
                    "-show_entries", "stream=codec_type,width,height:stream_disposition=attached_pic"
                            + ":stream_side_data=rotation:format=duration",
                    "-of", "json", url), timeout, output);
            if (!result.succeeded()) {
                // ffprobe repeats its input in error messages: keep the signed URL out of logs and the database.
                throw failure(sourceKey, result.tail(5).replace(url, sourceKey), result.exitCode());
            }
            return Optional.of(parse(sourceKey, result.output()));
        } finally {
            delete(output);
        }
    }

    SourceInfo parse(String sourceKey, String ffprobeJson) {
        ProbeOutput probe;
        try {
            probe = json.readValue(ffprobeJson, ProbeOutput.class);
        } catch (JacksonException e) {
            throw new TranscodingException("Unreadable ffprobe output for " + sourceKey, e);
        }
        List<Stream> streams = probe.streams() == null ? List.of() : probe.streams();
        Stream video = streams.stream()
                .filter(Stream::isVideo)
                .filter(s -> s.width() != null && s.height() != null && s.width() > 0 && s.height() > 0)
                .findFirst()
                .orElseThrow(() -> new InvalidMediaException(sourceKey + " has no video stream"));
        boolean quarterTurn = Math.abs(video.rotation()) % 180 == 90;
        int width = quarterTurn ? video.height() : video.width();
        int height = quarterTurn ? video.width() : video.height();
        boolean hasAudio = streams.stream().anyMatch(s -> "audio".equals(s.codecType()));
        return new SourceInfo(width, height, durationMs(probe.format()), hasAudio);
    }

    /** ffprobe fails both for files that are not media and for network problems; only the first is permanent. */
    private static RuntimeException failure(String sourceKey, String reason, int exitCode) {
        if (reason.contains("Invalid data found") || reason.contains("moov atom not found")
                || reason.contains("could not find codec parameters")) {
            return new InvalidMediaException(sourceKey + " is not a readable video: " + reason);
        }
        return new TranscodingException("ffprobe failed for " + sourceKey + " (exit " + exitCode + "): " + reason);
    }

    private static long durationMs(Format format) {
        if (format == null || format.duration() == null) {
            return 0;
        }
        try {
            return Math.round(Double.parseDouble(format.duration()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Path tempFile() {
        try {
            Files.createDirectories(properties.workDir());
            return Files.createTempFile(properties.workDir(), "probe-", ".json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Scratch file; the OS temp cleanup removes it eventually.
        }
    }
}
