package az.youtube.transcoding;

import az.youtube.config.TranscodingProperties;
import az.youtube.dto.event.TranscodeRequestedEvent;
import az.youtube.entity.Resolution;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for ffmpeg ({@code youtube.transcoding.engine=simulated}): checks that the original exists, takes as long
 * as a real job of that resolution would (configurable, 4K longest) and writes a rendition playlist without segments.
 * Used by the tests, whose "videos" are random bytes, and on machines without ffmpeg.
 */
@Component
@ConditionalOnProperty(prefix = "youtube.transcoding", name = "engine", havingValue = "simulated", matchIfMissing = true)
public class SimulatedTranscoder implements Transcoder {

    private final ObjectStorage storage;
    private final TranscodingProperties properties;

    public SimulatedTranscoder(ObjectStorage storage, TranscodingProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public Rendition transcode(TranscodeRequestedEvent job) {
        Resolution resolution = job.resolution();
        if (storage.objectSize(job.sourceS3Key()).isEmpty()) {
            throw new TranscodingException("Original " + job.sourceS3Key() + " not found");
        }
        work(properties.simulatedDurationOf(resolution));
        String key = StorageKeys.renditionPlaylist(job.videoId(), resolution);
        storage.putText(key, """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:4
                #EXT-X-PLAYLIST-TYPE:VOD
                # Simulated %s rendition: no segments were encoded.
                #EXT-X-ENDLIST
                """.formatted(resolution.label()),
                "application/vnd.apple.mpegurl", "public, max-age=31536000, immutable");
        return Rendition.nominal(key, resolution,
                FrameSize.of(resolution, job.sourceWidth(), job.sourceHeight()), job.hasAudio());
    }

    private static void work(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Shutdown: the offset is not committed, so the job is delivered again after restart.
            throw new TranscodingException("Interrupted", e);
        }
    }
}
