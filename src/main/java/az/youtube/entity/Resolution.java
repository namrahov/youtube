package az.youtube.entity;

import az.youtube.messaging.Topics;

import java.util.Arrays;
import java.util.Locale;

/** Output renditions. {@link #label()} is what the database and the API show (the PDF's 360p … 4K). */
public enum Resolution {

    P360("360p", 640, 360, 800_000, Topics.TRANSCODE_360P),
    P720("720p", 1280, 720, 2_800_000, Topics.TRANSCODE_720P),
    P1080("1080p", 1920, 1080, 5_000_000, Topics.TRANSCODE_1080P),
    P2160("4K", 3840, 2160, 16_000_000, Topics.TRANSCODE_4K);

    private final String label;
    private final int width;
    private final int height;
    /** Peak bits per second, announced in the HLS master playlist so the player can pick a rendition. */
    private final int bandwidth;
    private final String topic;

    Resolution(String label, int width, int height, int bandwidth, String topic) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.bandwidth = bandwidth;
        this.topic = topic;
    }

    public String label() {
        return label;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int bandwidth() {
        return bandwidth;
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
