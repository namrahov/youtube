package az.youtube.search;

import az.youtube.repository.StatsRepository;
import az.youtube.views.ViewAggregator;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Copies changed view counts into the search index (ranking uses them). Never per view: re-indexing on every view
 * would be tens of thousands of writes a second. The aggregator adds the ids of videos it counted to a Redis set;
 * this job takes them in chunks and sends one bulk update per chunk. The index lags the counter by up to
 * {@code stats-sync-interval-ms}, which ranking does not notice.
 */
@Component
public class SearchStatsSync {

    private static final Logger log = LoggerFactory.getLogger(SearchStatsSync.class);
    private static final int CHUNK = 500;

    private final StringRedisTemplate redis;
    private final StatsRepository stats;
    private final SearchIndex index;

    public SearchStatsSync(StringRedisTemplate redis, StatsRepository stats, SearchIndex index) {
        this.redis = redis;
        this.stats = stats;
        this.index = index;
    }

    @Scheduled(fixedDelayString = "${youtube.search.stats-sync-interval-ms}")
    public void sync() {
        List<String> ids;
        try {
            ids = redis.opsForSet().pop(ViewAggregator.SEARCH_DIRTY_KEY, CHUNK);
        } catch (RuntimeException e) {
            log.warn("View counts not copied to search: {}", e.getMessage());
            return;
        }
        while (ids != null && !ids.isEmpty()) {
            List<Long> videoIds = ids.stream().map(Long::valueOf).toList();
            try {
                index.updateViewCounts(stats.viewCounts(videoIds));
            } catch (RuntimeException e) {
                // Put them back: copied on the next run.
                redis.opsForSet().add(ViewAggregator.SEARCH_DIRTY_KEY, ids.toArray(String[]::new));
                log.warn("View counts not copied to search, will retry: {}", e.getMessage());
                return;
            }
            if (ids.size() < CHUNK) {
                return;
            }
            ids = redis.opsForSet().pop(ViewAggregator.SEARCH_DIRTY_KEY, CHUNK);
        }
    }
}
