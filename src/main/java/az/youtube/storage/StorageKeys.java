package az.youtube.storage;

import az.youtube.entity.Resolution;

/**
 * Object layout of one video:
 * <pre>
 * videos/123/original          uploaded file, never changed
 * videos/123/master.m3u8       HLS master playlist: lists the renditions that are ready
 * videos/123/360p/index.m3u8   rendition playlist (segments next to it)
 * videos/123/4k/index.m3u8
 * </pre>
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String original(long videoId) {
        return prefix(videoId) + "original";
    }

    public static String master(long videoId) {
        return prefix(videoId) + "master.m3u8";
    }

    public static String renditionPlaylist(long videoId, Resolution resolution) {
        return prefix(videoId) + renditionPlaylistPath(resolution);
    }

    /** Path of a rendition playlist relative to the master playlist. */
    public static String renditionPlaylistPath(Resolution resolution) {
        return resolution.pathSegment() + "/index.m3u8";
    }

    private static String prefix(long videoId) {
        return "videos/" + videoId + "/";
    }
}
