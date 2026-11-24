package az.youtube.service;

import static org.assertj.core.api.Assertions.assertThat;

import az.youtube.entity.Resolution;

import java.util.List;

import org.junit.jupiter.api.Test;

class ManifestWriterTest {

    @Test
    void listsOnlyCompletedRenditionsLowestFirst() {
        String manifest = ManifestWriter.render(List.of(Resolution.P1080, Resolution.P360));

        assertThat(manifest).isEqualTo("""
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                360p/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
                1080p/index.m3u8
                """);
    }

    @Test
    void fourKUsesALowercasePath() {
        assertThat(ManifestWriter.render(List.of(Resolution.P2160)))
                .contains("RESOLUTION=3840x2160\n4k/index.m3u8\n");
    }
}
