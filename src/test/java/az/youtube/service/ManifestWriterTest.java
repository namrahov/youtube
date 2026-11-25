package az.youtube.service;

import static org.assertj.core.api.Assertions.assertThat;

import az.youtube.entity.Resolution;
import az.youtube.entity.VariantStatus;
import az.youtube.repository.VariantRepository.Variant;

import java.util.List;

import org.junit.jupiter.api.Test;

class ManifestWriterTest {

    @Test
    void listsCompletedRenditionsLowestFirstWithMeasuredBandwidthAndCodecs() {
        String manifest = ManifestWriter.render(List.of(
                completed(Resolution.P1080, 1920, 1080, 5_900_000L, 4_100_000L, "avc1.64002a,mp4a.40.2"),
                completed(Resolution.P360, 640, 360, 950_000L, 700_000L, "avc1.64001e,mp4a.40.2")));

        assertThat(manifest).isEqualTo("""
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-STREAM-INF:BANDWIDTH=950000,AVERAGE-BANDWIDTH=700000,CODECS="avc1.64001e,mp4a.40.2",RESOLUTION=640x360
                360p/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5900000,AVERAGE-BANDWIDTH=4100000,CODECS="avc1.64002a,mp4a.40.2",RESOLUTION=1920x1080
                1080p/index.m3u8
                """);
    }

    @Test
    void portraitRenditionAnnouncesItsRealSize() {
        assertThat(ManifestWriter.render(List.of(
                completed(Resolution.P720, 720, 1280, 3_000_000L, 2_500_000L, "avc1.640020"))))
                .contains("CODECS=\"avc1.640020\",RESOLUTION=720x1280\n720p/index.m3u8\n");
    }

    @Test
    void variantWithoutMeasurementsFallsBackToTheLadder() {
        assertThat(ManifestWriter.render(List.of(completed(Resolution.P2160, null, null, null, null, null))))
                .contains("BANDWIDTH=20128000,AVERAGE-BANDWIDTH=16128000,CODECS=\"avc1.640034,mp4a.40.2\","
                        + "RESOLUTION=3840x2160\n4k/index.m3u8\n");
    }

    private static Variant completed(Resolution resolution, Integer width, Integer height, Long bandwidth,
                                     Long average, String codecs) {
        return new Variant(resolution, VariantStatus.COMPLETED, "key", 1, null, width, height, bandwidth, average,
                codecs);
    }
}
