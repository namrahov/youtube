package az.youtube.transcoding;

import az.youtube.entity.Resolution;

/** Turns the original into one HLS rendition and stores it. */
public interface Transcoder {

    /**
     * @return the key of the rendition's playlist
     * @throws TranscodingException if the rendition cannot be produced (the job is retried, then marked FAILED)
     */
    String transcode(long videoId, String sourceKey, Resolution resolution);
}
