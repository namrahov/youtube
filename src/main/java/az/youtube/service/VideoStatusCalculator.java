package az.youtube.service;

import az.youtube.entity.VariantStatus;
import az.youtube.entity.VideoStatus;

import java.util.Collection;

/**
 * The video status is derived from the variant rows every time, never counted up event by event. A duplicate
 * "720p completed" event then cannot turn "3 of 4 done" into "4 of 4 done": the 720p row is COMPLETED either way.
 */
public final class VideoStatusCalculator {

    private VideoStatusCalculator() {
    }

    public static VideoStatus derive(Collection<VariantStatus> variants) {
        long completed = variants.stream().filter(s -> s == VariantStatus.COMPLETED).count();
        long failed = variants.stream().filter(s -> s == VariantStatus.FAILED).count();
        if (variants.isEmpty()) {
            return VideoStatus.PROCESSING;
        }
        if (completed == variants.size()) {
            return VideoStatus.FULLY_PROCESSED;
        }
        if (completed > 0) {
            // Playback does not wait for the rest; a FAILED variant (say 4K) leaves the video PLAYABLE for good.
            return VideoStatus.PLAYABLE;
        }
        if (failed == variants.size()) {
            return VideoStatus.PROCESSING_FAILED;
        }
        return VideoStatus.PROCESSING;
    }
}
