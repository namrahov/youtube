package az.youtube.views;

import az.youtube.dto.event.ViewEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides which views of one batch count. Two different kinds of duplicate:
 * <ul>
 *   <li><b>duplicate event</b>: the same eventId again (a client or producer retry). Never counted twice.</li>
 *   <li><b>repeat viewer</b>: a new view, but the same user already counted for this video within the window
 *       (refreshing the page). A business rule, not a technical duplicate.</li>
 * </ul>
 * Keys already seen in earlier batches come from Redis ({@code alreadySeen}); keys seen earlier in this batch are
 * tracked here, so ten refreshes inside one batch count once as well.
 */
public final class ViewBatch {

    static final String EVENT_KEY_PREFIX = "views:event:";
    static final String VIEWER_KEY_PREFIX = "views:viewer:";

    /**
     * @param counts       views to add per video id
     * @param eventKeys    to remember for the event TTL (every new event, counted or not)
     * @param viewerKeys   to remember for the repeat-viewer window (counted views only)
     */
    public record Result(Map<Long, Long> counts, List<String> eventKeys, List<String> viewerKeys,
                         long duplicateEvents, long repeatViewers) {

        public long counted() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private ViewBatch() {
    }

    public static String eventKey(ViewEvent event) {
        return EVENT_KEY_PREFIX + event.eventId();
    }

    public static String viewerKey(ViewEvent event) {
        return VIEWER_KEY_PREFIX + event.videoId() + ":" + event.userId();
    }

    /** All keys whose presence in Redis has to be checked before counting this batch. */
    public static List<String> keysToCheck(List<ViewEvent> events) {
        Set<String> keys = new LinkedHashSet<>();
        for (ViewEvent event : events) {
            keys.add(eventKey(event));
            keys.add(viewerKey(event));
        }
        return new ArrayList<>(keys);
    }

    public static Result count(List<ViewEvent> events, Predicate<String> alreadySeen) {
        Map<Long, Long> counts = new HashMap<>();
        Set<String> seenEvents = new HashSet<>();
        Set<String> seenViewers = new HashSet<>();
        List<String> eventKeys = new ArrayList<>();
        List<String> viewerKeys = new ArrayList<>();
        long duplicateEvents = 0;
        long repeatViewers = 0;
        for (ViewEvent event : events) {
            String eventKey = eventKey(event);
            if (alreadySeen.test(eventKey) || !seenEvents.add(eventKey)) {
                duplicateEvents++;
                continue;
            }
            eventKeys.add(eventKey);
            String viewerKey = viewerKey(event);
            if (alreadySeen.test(viewerKey) || !seenViewers.add(viewerKey)) {
                repeatViewers++;
                continue;
            }
            viewerKeys.add(viewerKey);
            counts.merge(event.videoId(), 1L, Long::sum);
        }
        return new Result(counts, eventKeys, viewerKeys, duplicateEvents, repeatViewers);
    }
}
