package az.youtube.dto.event;

import az.youtube.entity.Resolution;

/** One job: one video, one resolution. Goes to that resolution's own topic. */
public record TranscodeRequestedEvent(long videoId, Resolution resolution, String sourceS3Key) {

    public static final String TYPE = "TranscodeRequested";
}
