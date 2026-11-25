package az.youtube.transcoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HlsPlaylistTest {

    private static final String PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.000000,
            seg_00000.m4s
            #EXTINF:4.000000,
            seg_00001.m4s
            #EXTINF:0.500000,
            seg_00002.m4s
            #EXT-X-ENDLIST
            """;

    @Test
    void readsSegmentsWithTheirDurationsAndSizes() {
        Map<String, Long> sizes = Map.of("seg_00000.m4s", 500_000L, "seg_00001.m4s", 1_000_000L,
                "seg_00002.m4s", 400_000L);

        assertThat(HlsPlaylist.segments(PLAYLIST, sizes::get)).containsExactly(
                new HlsPlaylist.Segment("seg_00000.m4s", 4.0, 500_000),
                new HlsPlaylist.Segment("seg_00001.m4s", 4.0, 1_000_000),
                new HlsPlaylist.Segment("seg_00002.m4s", 0.5, 400_000));
    }

    @Test
    void peakIsTheBusiestSegmentAndAShortLastSegmentDoesNotCountAlone() {
        var segments = List.of(
                new HlsPlaylist.Segment("a", 4.0, 500_000),   // 1.0 Mbit/s
                new HlsPlaylist.Segment("b", 4.0, 1_000_000), // 2.0 Mbit/s
                new HlsPlaylist.Segment("c", 0.5, 400_000));  // 6.4 Mbit/s alone, but only 0.5 s long

        HlsPlaylist.Bitrate bitrate = HlsPlaylist.bitrate(segments, 4.0);

        assertThat(bitrate.peak()).isEqualTo(2_000_000);
        // 1.9 MB = 15.2 Mbit over 8.5 s
        assertThat(bitrate.average()).isEqualTo(1_788_235);
    }

    @Test
    void videoShorterThanHalfATargetDurationIsMeasuredAsIs() {
        var bitrate = HlsPlaylist.bitrate(List.of(new HlsPlaylist.Segment("a", 1.0, 125_000)), 4.0);

        assertThat(bitrate.peak()).isEqualTo(1_000_000);
        assertThat(bitrate.average()).isEqualTo(1_000_000);
    }
}
