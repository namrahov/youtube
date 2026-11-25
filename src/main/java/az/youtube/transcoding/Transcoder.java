package az.youtube.transcoding;

import az.youtube.dto.event.TranscodeRequestedEvent;

/** Turns the original into one HLS rendition and stores it. */
public interface Transcoder {

    /**
     * Produces the job's rendition. Safe to repeat: the output keys are fixed, so a retry overwrites them.
     *
     * @throws TranscodingException if the rendition cannot be produced (the job is retried, then marked FAILED)
     */
    Rendition transcode(TranscodeRequestedEvent job);
}
