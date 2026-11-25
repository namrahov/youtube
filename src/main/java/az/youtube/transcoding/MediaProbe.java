package az.youtube.transcoding;

import java.util.Optional;

/** Reads the original's video size and duration before the jobs are planned. */
public interface MediaProbe {

    /**
     * @return the source's properties, or empty if this probe cannot tell (then every configured rendition is made)
     * @throws InvalidMediaException if the file is not a playable video: retrying cannot help
     */
    Optional<SourceInfo> probe(String sourceKey);
}
