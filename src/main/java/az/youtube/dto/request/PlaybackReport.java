package az.youtube.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What a player measured during one playback session. Each field is optional: a player sends the startup time as
 * soon as the first frame shows, and the stall figures when the session ends.
 *
 * @param startupMs from pressing play (or loading the page) to the first frame
 * @param watchedMs time spent playing
 * @param stalledMs time spent waiting for data after playback had started (rebuffering)
 * @param stalls    how many times playback stalled
 */
public record PlaybackReport(
        @Min(1) long videoId,
        @PositiveOrZero @Max(600_000) Long startupMs,
        @PositiveOrZero Long watchedMs,
        @PositiveOrZero Long stalledMs,
        @PositiveOrZero @Max(10_000) Integer stalls) {
}
