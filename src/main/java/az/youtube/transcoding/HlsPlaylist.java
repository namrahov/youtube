package az.youtube.transcoding;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/** Reads the segment list of a VOD rendition playlist and measures its bit rate. */
final class HlsPlaylist {

    record Segment(String uri, double durationSeconds, long bytes) {
    }

    record Bitrate(long peak, long average) {
    }

    private HlsPlaylist() {
    }

    /** @param sizeOf size in bytes of a segment file, by its URI */
    static List<Segment> segments(String playlist, ToLongFunction<String> sizeOf) {
        List<Segment> segments = new ArrayList<>();
        Double duration = null;
        for (String line : playlist.lines().map(String::strip).toList()) {
            if (line.startsWith("#EXTINF:")) {
                String value = line.substring("#EXTINF:".length());
                int comma = value.indexOf(',');
                duration = Double.parseDouble(comma < 0 ? value : value.substring(0, comma));
            } else if (!line.isEmpty() && !line.startsWith("#") && duration != null) {
                segments.add(new Segment(line, duration, sizeOf.applyAsLong(line)));
                duration = null;
            }
        }
        return segments;
    }

    /**
     * HLS BANDWIDTH is the peak segment bit rate: the highest rate of any run of consecutive segments lasting at least
     * half the target duration (RFC 8216 §4.3.4.2). A short run at the very end (the last, partial segment) does not
     * count on its own, unless the whole video is that short.
     */
    static Bitrate bitrate(List<Segment> segments, double targetDurationSeconds) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Playlist has no segments");
        }
        double minWindow = targetDurationSeconds / 2;
        long totalBytes = 0;
        double totalSeconds = 0;
        double peak = 0;
        for (int i = 0; i < segments.size(); i++) {
            long bytes = 0;
            double seconds = 0;
            int j = i;
            while (j < segments.size() && seconds < minWindow) {
                bytes += segments.get(j).bytes();
                seconds += segments.get(j).durationSeconds();
                j++;
            }
            if (seconds > 0 && (seconds >= minWindow || i == 0)) {
                peak = Math.max(peak, bytes * 8 / seconds);
            }
            totalBytes += segments.get(i).bytes();
            totalSeconds += segments.get(i).durationSeconds();
        }
        if (totalSeconds <= 0) {
            throw new IllegalArgumentException("Playlist has no duration");
        }
        return new Bitrate(Math.round(Math.ceil(peak)), Math.round(totalBytes * 8 / totalSeconds));
    }
}
