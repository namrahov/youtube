package az.youtube.transcoding;

import az.youtube.entity.Resolution;

/** Output size of one rendition. */
public record FrameSize(int width, int height) {

    /**
     * Scales the source so its shorter side equals the rendition's height and keeps the aspect ratio: a 16:9 source
     * at 720p is 1280×720, a 9:16 phone video at 720p is 720×1280. Both sides are even (H.264 with 4:2:0 chroma needs
     * that). Without source dimensions the nominal 16:9 size is used.
     */
    public static FrameSize of(Resolution resolution, Integer sourceWidth, Integer sourceHeight) {
        if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) {
            return new FrameSize(resolution.width(), resolution.height());
        }
        int shortSide = resolution.height();
        if (sourceWidth >= sourceHeight) {
            return new FrameSize(even((double) sourceWidth * shortSide / sourceHeight), shortSide);
        }
        return new FrameSize(shortSide, even((double) sourceHeight * shortSide / sourceWidth));
    }

    private static int even(double value) {
        return (int) Math.max(2, Math.round(value / 2) * 2);
    }
}
