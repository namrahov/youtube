package az.youtube.service;

import static az.youtube.entity.VariantStatus.COMPLETED;
import static az.youtube.entity.VariantStatus.FAILED;
import static az.youtube.entity.VariantStatus.PENDING;
import static az.youtube.entity.VariantStatus.PROCESSING;
import static az.youtube.entity.VariantStatus.SKIPPED;
import static org.assertj.core.api.Assertions.assertThat;

import az.youtube.entity.VideoStatus;

import java.util.List;

import org.junit.jupiter.api.Test;

class VideoStatusCalculatorTest {

    @Test
    void nothingReadyYetIsProcessing() {
        assertThat(VideoStatusCalculator.derive(List.of(PENDING, PROCESSING, PENDING, PENDING)))
                .isEqualTo(VideoStatus.PROCESSING);
    }

    @Test
    void firstCompletedVariantMakesTheVideoPlayable() {
        assertThat(VideoStatusCalculator.derive(List.of(COMPLETED, PROCESSING, PENDING, PENDING)))
                .isEqualTo(VideoStatus.PLAYABLE);
    }

    @Test
    void stillPlayableWhileOnlyFourKIsRunning() {
        assertThat(VideoStatusCalculator.derive(List.of(COMPLETED, COMPLETED, COMPLETED, PROCESSING)))
                .isEqualTo(VideoStatus.PLAYABLE);
    }

    @Test
    void allCompletedIsFullyProcessed() {
        assertThat(VideoStatusCalculator.derive(List.of(COMPLETED, COMPLETED, COMPLETED, COMPLETED)))
                .isEqualTo(VideoStatus.FULLY_PROCESSED);
    }

    @Test
    void failedFourKKeepsTheVideoPlayableButNeverFullyProcessed() {
        assertThat(VideoStatusCalculator.derive(List.of(COMPLETED, COMPLETED, COMPLETED, FAILED)))
                .isEqualTo(VideoStatus.PLAYABLE);
    }

    @Test
    void everyVariantFailedIsProcessingFailed() {
        assertThat(VideoStatusCalculator.derive(List.of(FAILED, FAILED, FAILED, FAILED)))
                .isEqualTo(VideoStatus.PROCESSING_FAILED);
    }

    @Test
    void skippedRenditionsAreNotRequiredForFullyProcessed() {
        assertThat(VideoStatusCalculator.derive(List.of(COMPLETED, COMPLETED, SKIPPED, SKIPPED)))
                .isEqualTo(VideoStatus.FULLY_PROCESSED);
    }

    @Test
    void skippedRenditionsDoNotMakeAVideoPlayable() {
        assertThat(VideoStatusCalculator.derive(List.of(PROCESSING, SKIPPED, SKIPPED, SKIPPED)))
                .isEqualTo(VideoStatus.PROCESSING);
        assertThat(VideoStatusCalculator.derive(List.of(FAILED, SKIPPED, SKIPPED, SKIPPED)))
                .isEqualTo(VideoStatus.PROCESSING_FAILED);
    }

    @Test
    void someFailedOthersStillRunningIsProcessing() {
        assertThat(VideoStatusCalculator.derive(List.of(FAILED, PROCESSING, PENDING, PENDING)))
                .isEqualTo(VideoStatus.PROCESSING);
    }
}
