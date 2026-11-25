package az.youtube.transcoding;

import static org.assertj.core.api.Assertions.assertThat;

import az.youtube.entity.Resolution;

import org.junit.jupiter.api.Test;

class FrameSizeTest {

    @Test
    void landscapeSourceKeepsItsAspectRatio() {
        assertThat(FrameSize.of(Resolution.P720, 3840, 2160)).isEqualTo(new FrameSize(1280, 720));
        // 4:3 source at 360p
        assertThat(FrameSize.of(Resolution.P360, 1440, 1080)).isEqualTo(new FrameSize(480, 360));
    }

    @Test
    void portraitSourceScalesItsShorterSide() {
        assertThat(FrameSize.of(Resolution.P720, 1080, 1920)).isEqualTo(new FrameSize(720, 1280));
    }

    @Test
    void sidesAreAlwaysEven() {
        // 2.39:1 cinema: 1080 · 2.39 = 2581.2 at 1080p → 1720.8 at 720p → rounded to an even 1720
        FrameSize size = FrameSize.of(Resolution.P720, 2582, 1080);
        assertThat(size.width() % 2).isZero();
        assertThat(size.height()).isEqualTo(720);
    }

    @Test
    void unknownSourceUsesTheNominalSixteenByNineSize() {
        assertThat(FrameSize.of(Resolution.P1080, null, null)).isEqualTo(new FrameSize(1920, 1080));
    }
}
