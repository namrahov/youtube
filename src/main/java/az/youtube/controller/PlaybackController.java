package az.youtube.controller;

import az.youtube.dto.request.PlaybackReport;
import az.youtube.metrics.AppMetrics;
import jakarta.validation.Valid;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Player quality of experience: startup latency (PDF target p95 under 2 s) and rebuffering can only be measured in
 * the player, so players report them here and they become metrics. No user header is required: browsers send these
 * with {@code navigator.sendBeacon} when the page closes, and that cannot set headers.
 */
@RestController
public class PlaybackController {

    private final AppMetrics metrics;

    public PlaybackController(AppMetrics metrics) {
        this.metrics = metrics;
    }

    @PostMapping("/api/playback/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody PlaybackReport report) {
        if (report.startupMs() != null) {
            metrics.playbackStartup(Duration.ofMillis(report.startupMs()));
        }
        if (report.watchedMs() != null && report.watchedMs() > 0) {
            long stalled = report.stalledMs() == null ? 0 : report.stalledMs();
            metrics.playbackRebufferRatio((double) stalled / (report.watchedMs() + stalled));
            if (report.stalls() != null) {
                metrics.playbackStalls(report.stalls());
            }
        }
    }
}
