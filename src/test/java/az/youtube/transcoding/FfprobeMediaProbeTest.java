package az.youtube.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.youtube.config.TranscodingProperties;
import az.youtube.entity.Resolution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FfprobeMediaProbeTest {

    private final FfprobeMediaProbe probe = new FfprobeMediaProbe(null, properties(), JsonMapper.builder().build());

    @Test
    void readsSizeDurationAndAudio() {
        SourceInfo info = probe.parse("k", """
                {"streams":[{"codec_type":"video","width":1920,"height":1080,"disposition":{"attached_pic":0}},
                            {"codec_type":"audio","disposition":{"attached_pic":0}}],
                 "format":{"duration":"12.480000"}}
                """);

        assertThat(info).isEqualTo(new SourceInfo(1920, 1080, 12_480, true));
    }

    @Test
    void phoneVideoWithRotationFlagIsPortrait() {
        SourceInfo info = probe.parse("k", """
                {"streams":[{"codec_type":"video","width":1920,"height":1080,
                             "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]}],
                 "format":{"duration":"3.0"}}
                """);

        assertThat(info.width()).isEqualTo(1080);
        assertThat(info.height()).isEqualTo(1920);
        assertThat(info.hasAudio()).isFalse();
    }

    @Test
    void audioFileWithCoverArtIsNotAVideo() {
        assertThatThrownBy(() -> probe.parse("k", """
                {"streams":[{"codec_type":"audio"},
                            {"codec_type":"video","width":600,"height":600,"disposition":{"attached_pic":1}}],
                 "format":{"duration":"180.0"}}
                """)).isInstanceOf(InvalidMediaException.class);
    }

    @Test
    void sourceSmallerThanARenditionSkipsItButAlwaysKeepsTheLowest() {
        SourceInfo hd = new SourceInfo(1280, 720, 1000, true);
        assertThat(hd.worthEncoding(Resolution.P720, Resolution.P360)).isTrue();
        assertThat(hd.worthEncoding(Resolution.P1080, Resolution.P360)).isFalse();

        SourceInfo tiny = new SourceInfo(320, 240, 1000, true);
        assertThat(tiny.worthEncoding(Resolution.P360, Resolution.P360)).isTrue();
        assertThat(tiny.worthEncoding(Resolution.P720, Resolution.P360)).isFalse();
    }

    private static TranscodingProperties properties() {
        return new TranscodingProperties(TranscodingProperties.Engine.FFMPEG, List.of(Resolution.values()), 1,
                Duration.ofSeconds(1), Map.of(), Duration.ofMinutes(1), Map.of(),
                new TranscodingProperties.Ffmpeg("ffmpeg", "ffprobe", "veryfast", Duration.ofSeconds(4),
                        Duration.ofSeconds(2), Duration.ofMinutes(1), Path.of("build", "tmp")));
    }
}
