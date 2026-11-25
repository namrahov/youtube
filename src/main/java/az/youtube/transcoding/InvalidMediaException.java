package az.youtube.transcoding;

/** The uploaded file is not a video ffmpeg can read. Permanent: the upload is rejected instead of retried. */
public class InvalidMediaException extends RuntimeException {

    public InvalidMediaException(String message) {
        super(message);
    }
}
