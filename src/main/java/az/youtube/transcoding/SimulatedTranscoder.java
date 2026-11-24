package az.youtube.transcoding;

import az.youtube.config.TranscodingProperties;
import az.youtube.entity.Resolution;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * Stand-in for ffmpeg: checks that the original exists, takes as long as a real job of that resolution would
 * (configurable, 4K longest) and writes a rendition playlist without segments. Real encoding and segmenting
 * (HLS/DASH, PDF section 5) is a later step; everything around it — jobs, retries, statuses, manifest — is real.
 */
@Component
public class SimulatedTranscoder implements Transcoder {

    private final ObjectStorage storage;
    private final TranscodingProperties properties;

    public SimulatedTranscoder(ObjectStorage storage, TranscodingProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String transcode(long videoId, String sourceKey, Resolution resolution) {
        if (storage.objectSize(sourceKey).isEmpty()) {
            throw new TranscodingException("Original " + sourceKey + " not found");
        }
        work(properties.simulatedDurationOf(resolution));
        String key = StorageKeys.renditionPlaylist(videoId, resolution);
        storage.putText(key, """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:4
                #EXT-X-PLAYLIST-TYPE:VOD
                # Simulated %s rendition: no segments were encoded.
                #EXT-X-ENDLIST
                """.formatted(resolution.label()),
                "application/vnd.apple.mpegurl", "public, max-age=31536000, immutable");
        return key;
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
