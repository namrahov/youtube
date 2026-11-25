package az.youtube.views;

import static org.assertj.core.api.Assertions.assertThat;

import az.youtube.config.ViewsProperties;
import az.youtube.dto.event.ViewEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ViewBatchTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    @Test
    void retriedEventCountsOnceAndRefreshingUserCountsOnce() {
        List<ViewEvent> events = List.of(
                view(A, 1, 100),
                view(A, 1, 100),   // the same request retried
                view(B, 1, 100),   // user 100 watched video 1 again (refresh)
                view(C, 1, 200),
                view(D, 2, 100));  // the same user, another video: counts

        ViewBatch.Result result = ViewBatch.count(events, key -> false);

        assertThat(result.counts()).isEqualTo(Map.of(1L, 2L, 2L, 1L));
        assertThat(result.duplicateEvents()).isEqualTo(1);
        assertThat(result.repeatViewers()).isEqualTo(1);
        assertThat(result.eventKeys()).hasSize(4);
        assertThat(result.viewerKeys()).containsExactlyInAnyOrder(
                "views:viewer:1:100", "views:viewer:1:200", "views:viewer:2:100");
    }

    @Test
    void keysFromEarlierBatchesAreHonoured() {
        Set<String> inRedis = Set.of("views:event:" + A, "views:viewer:1:200");

        ViewBatch.Result result = ViewBatch.count(List.of(view(A, 1, 100), view(C, 1, 200), view(D, 1, 300)),
                inRedis::contains);

        assertThat(result.counts()).isEqualTo(Map.of(1L, 1L));
        assertThat(result.duplicateEvents()).isEqualTo(1);
        assertThat(result.repeatViewers()).isEqualTo(1);
    }

    @Test
    void keysToCheckAreUnique() {
        assertThat(ViewBatch.keysToCheck(List.of(view(A, 1, 100), view(A, 1, 100))))
                .containsExactly("views:event:" + A, "views:viewer:1:100");
    }

    @Test
    void shortVideoCountsWhenWatchedToTheEnd() {
        ViewsProperties properties = new ViewsProperties(Duration.ofSeconds(30), Duration.ofHours(24),
                Duration.ofHours(24), 2000, 5000);

        assertThat(properties.requiredWatch(12_400L)).isEqualTo(Duration.ofSeconds(12));
        assertThat(properties.requiredWatch(600_000L)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.requiredWatch(null)).isEqualTo(Duration.ofSeconds(30));
    }

    private static ViewEvent view(UUID eventId, long videoId, long userId) {
        return new ViewEvent(eventId, videoId, userId, Instant.now());
    }
}
