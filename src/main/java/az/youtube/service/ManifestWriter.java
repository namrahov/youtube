package az.youtube.service;

import az.youtube.entity.Resolution;
import az.youtube.repository.VariantRepository.Variant;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.util.Collection;
import java.util.Comparator;

import org.springframework.stereotype.Component;

/**
 * Writes the HLS master playlist, listing only the renditions that are COMPLETED. It is rewritten every time one
 * more rendition finishes, so a player that opens the video later sees the new quality (e.g. 4K) as well.
 * <p>
 * The player chooses between the listed renditions on its own (adaptive bitrate): it measures how fast segments
 * arrive and how full its buffer is, and compares that with each rendition's BANDWIDTH. That is why BANDWIDTH is the
 * measured peak of the real segments, not a wish, and why CODECS is given: a player skips renditions it cannot decode
 * without downloading them first.
 */
@Component
public class ManifestWriter {

    static final String CONTENT_TYPE = "application/vnd.apple.mpegurl";
    /** Short: the master playlist changes while renditions finish. Rendition files never change. */
    static final String CACHE_CONTROL = "public, max-age=10";

    private final ObjectStorage storage;

    public ManifestWriter(ObjectStorage storage) {
        this.storage = storage;
    }

    /** @return the master playlist's key */
    public String write(long videoId, Collection<Variant> completed) {
        String key = StorageKeys.master(videoId);
        storage.putText(key, render(completed), CONTENT_TYPE, CACHE_CONTROL);
        return key;
    }

    static String render(Collection<Variant> completed) {
        StringBuilder m = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        completed.stream().sorted(Comparator.comparing(Variant::resolution)).forEach(v -> {
            Resolution r = v.resolution();
            // Variants finished before the rendition columns existed fall back to the ladder's nominal values.
            long bandwidth = v.bandwidth() != null ? v.bandwidth() : r.nominalBandwidth();
            long average = v.averageBandwidth() != null ? v.averageBandwidth() : r.nominalAverageBandwidth();
            String codecs = v.codecs() != null ? v.codecs() : r.codecs(true);
            int width = v.width() != null ? v.width() : r.width();
            int height = v.height() != null ? v.height() : r.height();
            m.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
                    .append(",AVERAGE-BANDWIDTH=").append(average)
                    .append(",CODECS=\"").append(codecs).append('"')
                    .append(",RESOLUTION=").append(width).append('x').append(height).append('\n')
                    .append(StorageKeys.renditionPlaylistPath(r)).append('\n');
        });
        return m.toString();
    }
}
