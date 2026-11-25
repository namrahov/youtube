package az.youtube.service;

import az.youtube.entity.VariantStatus;
import az.youtube.entity.VideoStatus;

import java.util.Collection;
import java.util.List;

/**
 * The video status is derived from the variant rows every time, never counted up event by event. A duplicate
 * "720p completed" event then cannot turn "3 of 4 done" into "4 of 4 done": the 720p row is COMPLETED either way.
 * SKIPPED variants (larger than the source) are not required: a 1080p upload with 360p–1080p done is FULLY_PROCESSED.
 */
public final class VideoStatusCalculator {

    private VideoStatusCalculator() {
    }

    public static VideoStatus derive(Collection<VariantStatus> variants) {
        List<VariantStatus> required = variants.stream().filter(s -> s != VariantStatus.SKIPPED).toList();
        long completed = required.stream().filter(s -> s == VariantStatus.COMPLETED).count();
        long failed = required.stream().filter(s -> s == VariantStatus.FAILED).count();
        if (required.isEmpty()) {
            return VideoStatus.PROCESSING;
        }
        if (completed == required.size()) {
            return VideoStatus.FULLY_PROCESSED;
        }
        if (completed > 0) {
            // Playback does not wait for the rest; a FAILED variant (say 4K) leaves the video PLAYABLE for good.
            return VideoStatus.PLAYABLE;
        }
        if (failed == required.size()) {
            return VideoStatus.PROCESSING_FAILED;
        }
        return VideoStatus.PROCESSING;
    }
}
