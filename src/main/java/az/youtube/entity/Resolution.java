package az.youtube.entity;

import az.youtube.messaging.Topics;

import java.util.Arrays;
import java.util.Locale;

/**
 * The bitrate ladder: one HLS rendition per value. {@link #label()} is what the database and the API show (the PDF's
 * 360p … 4K). Every rendition is H.264 High + AAC-LC stereo, so a player can switch between them at any segment.
 */
public enum Resolution {

    P360("360p", 640, 360, 800, 1_000, 30, Topics.TRANSCODE_360P),
    P720("720p", 1280, 720, 2_800, 3_500, 32, Topics.TRANSCODE_720P),
    P1080("1080p", 1920, 1080, 5_000, 6_000, 42, Topics.TRANSCODE_1080P),
    P2160("4K", 3840, 2160, 16_000, 20_000, 52, Topics.TRANSCODE_4K);

    /** Every rendition carries the same audio track: AAC-LC, stereo, 48 kHz. */
    public static final int AUDIO_KBPS = 128;

    private final String label;
    private final int width;
    private final int height;
    /** Average video bitrate the encoder aims for. */
    private final int videoKbps;
    /** Cap for the encoder's rate control (with a 2× buffer), so the announced BANDWIDTH stays honest. */
    private final int maxVideoKbps;
    /** H.264 level × 10 (4.2 → 42): fixed per rendition, so the CODECS string is known before encoding. */
    private final int h264Level;
    private final String topic;

    Resolution(String label, int width, int height, int videoKbps, int maxVideoKbps, int h264Level, String topic) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.videoKbps = videoKbps;
        this.maxVideoKbps = maxVideoKbps;
        this.h264Level = h264Level;
        this.topic = topic;
    }

    public String label() {
        return label;
    }

    /** Nominal width for a 16:9 source; the real output keeps the source's aspect ratio. */
    public int width() {
        return width;
    }

    /** Height of a landscape output, i.e. the length of the shorter side. */
    public int height() {
        return height;
    }

    public int videoKbps() {
        return videoKbps;
    }

    public int maxVideoKbps() {
        return maxVideoKbps;
    }

    /** e.g. {@code 4.2}, for ffmpeg's {@code -level:v}. */
    public String h264LevelName() {
        return h264Level / 10 + "." + h264Level % 10;
    }

    /**
     * RFC 6381 codec string for the HLS CODECS attribute: {@code avc1.64002a} is H.264 High (0x64), no constraint
     * flags, level 4.2 (0x2a); {@code mp4a.40.2} is AAC-LC.
     */
    public String codecs(boolean withAudio) {
        String video = "avc1.6400" + String.format(Locale.ROOT, "%02x", h264Level);
        return withAudio ? video + ",mp4a.40.2" : video;
    }

    /** Expected peak bits per second when nothing was measured (simulated transcoder). */
    public long nominalBandwidth() {
        return (maxVideoKbps + AUDIO_KBPS) * 1000L;
    }

    public long nominalAverageBandwidth() {
        return (videoKbps + AUDIO_KBPS) * 1000L;
    }

    /** Topic of this resolution's transcoding jobs. */
    public String topic() {
        return topic;
    }

    /** Directory of this rendition under the video's storage prefix, e.g. {@code 4k}. */
    public String pathSegment() {
        return label.toLowerCase(Locale.ROOT);
    }

    public static Resolution fromLabel(String label) {
        return Arrays.stream(values())
                .filter(r -> r.label.equals(label))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown resolution " + label));
    }
}
