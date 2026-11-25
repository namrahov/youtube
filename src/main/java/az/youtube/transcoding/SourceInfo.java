package az.youtube.transcoding;

import az.youtube.entity.Resolution;

/**
 * What ffprobe found in the original. Width and height are the <em>displayed</em> size: a phone video stored as
 * 1920×1080 with a 90° rotation flag is 1080×1920 here, because ffmpeg rotates it before scaling.
 */
public record SourceInfo(int width, int height, long durationMs, boolean hasAudio) {

    public int shortSide() {
        return Math.min(width, height);
    }

    /**
     * No upscaling: a 1080p source gets no 4K rendition. The lowest rendition of the ladder is always made, even
     * from a smaller source, so every valid video gets at least one playable variant.
     */
    public boolean worthEncoding(Resolution resolution, Resolution lowestPlanned) {
        return resolution == lowestPlanned || resolution.height() <= shortSide();
    }
}
