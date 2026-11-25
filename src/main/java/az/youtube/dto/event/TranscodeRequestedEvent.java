package az.youtube.dto.event;

import az.youtube.entity.Resolution;

/**
 * One job: one video, one resolution. Goes to that resolution's own topic.
 * The source fields come from ffprobe; they are null when the source was not probed (simulated engine).
 */
public record TranscodeRequestedEvent(long videoId, Resolution resolution, String sourceS3Key,
                                      Integer sourceWidth, Integer sourceHeight, Boolean sourceHasAudio) {

    public static final String TYPE = "TranscodeRequested";

    /** Audio is assumed when unknown: ffmpeg maps it only if it exists, but the CODECS string must guess. */
    public boolean hasAudio() {
        return sourceHasAudio == null || sourceHasAudio;
    }
}
