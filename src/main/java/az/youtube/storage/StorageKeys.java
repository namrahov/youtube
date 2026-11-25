package az.youtube.storage;

import az.youtube.entity.Resolution;

/**
 * Object layout. Originals and streams live under different prefixes so the streams can be opened to a CDN
 * (or, locally, to anonymous GET) without exposing the uploaded originals:
 * <pre>
 * originals/123                      uploaded file, private, never changed
 * streams/123/master.m3u8            HLS master playlist: lists the renditions that are ready
 * streams/123/360p/index.m3u8        rendition playlist (VOD)
 * streams/123/360p/init.mp4          fMP4 init segment (codec configuration)
 * streams/123/360p/seg_00001.m4s     fMP4 media segments
 * streams/123/4k/...
 * </pre>
 */
public final class StorageKeys {

    /** Everything under this prefix is what players fetch (through the CDN). */
    public static final String STREAMS_PREFIX = "streams/";

    private StorageKeys() {
    }

    public static String original(long videoId) {
        return "originals/" + videoId;
    }

    public static String master(long videoId) {
        return streams(videoId) + "master.m3u8";
    }

    public static String renditionPlaylist(long videoId, Resolution resolution) {
        return streams(videoId) + renditionPlaylistPath(resolution);
    }

    /** Directory of one rendition, ending in {@code /}: its playlist, init segment and media segments. */
    public static String renditionDir(long videoId, Resolution resolution) {
        return streams(videoId) + resolution.pathSegment() + "/";
    }

    /** Path of a rendition playlist relative to the master playlist. */
    public static String renditionPlaylistPath(Resolution resolution) {
        return resolution.pathSegment() + "/index.m3u8";
    }

    private static String streams(long videoId) {
        return STREAMS_PREFIX + videoId + "/";
    }
}
