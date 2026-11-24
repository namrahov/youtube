package az.youtube.service;

import az.youtube.entity.Resolution;
import az.youtube.storage.ObjectStorage;
import az.youtube.storage.StorageKeys;

import java.util.Collection;

import org.springframework.stereotype.Component;

/**
 * Writes the HLS master playlist, listing only the renditions that are COMPLETED. It is rewritten every time one
 * more rendition finishes, so a player that opens the video later sees the new quality (e.g. 4K) as well.
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
    public String write(long videoId, Collection<Resolution> completed) {
        String key = StorageKeys.master(videoId);
        storage.putText(key, render(completed), CONTENT_TYPE, CACHE_CONTROL);
        return key;
    }

    static String render(Collection<Resolution> completed) {
        StringBuilder m = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        completed.stream().sorted().forEach(r -> m
                .append("#EXT-X-STREAM-INF:BANDWIDTH=").append(r.bandwidth())
                .append(",RESOLUTION=").append(r.width()).append('x').append(r.height()).append('\n')
                .append(StorageKeys.renditionPlaylistPath(r)).append('\n'));
        return m.toString();
    }
}
