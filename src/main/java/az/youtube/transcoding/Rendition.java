package az.youtube.transcoding;

import az.youtube.entity.Resolution;

/**
 * One finished rendition, as the master playlist announces it.
 *
 * @param playlistKey      object key of the rendition playlist
 * @param bandwidth        peak bits per second of its segments (HLS BANDWIDTH)
 * @param averageBandwidth average bits per second over the whole video (HLS AVERAGE-BANDWIDTH)
 * @param codecs           RFC 6381 codec string (HLS CODECS), e.g. {@code avc1.64001e,mp4a.40.2}
 */
public record Rendition(String playlistKey, int width, int height, long bandwidth, long averageBandwidth,
                        String codecs) {

    /** The ladder's expected values, for a rendition whose segments were not measured. */
    public static Rendition nominal(String playlistKey, Resolution resolution, FrameSize size, boolean withAudio) {
        return new Rendition(playlistKey, size.width(), size.height(), resolution.nominalBandwidth(),
                resolution.nominalAverageBandwidth(), resolution.codecs(withAudio));
    }
}
