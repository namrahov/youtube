package az.youtube.search;

import az.youtube.dto.event.VideoChangedEvent;
import az.youtube.entity.VideoEntity;
import az.youtube.messaging.Topics;
import az.youtube.repository.StatsRepository;
import az.youtube.repository.VideoRepository;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The indexing pipeline: {@code video.changed} (written by the outbox in the same transaction as the change) →
 * read the video's <em>current</em> state from PostgreSQL → upsert or delete its document.
 * <ul>
 *   <li>Idempotent: the document id is the video id, so a repeated event rewrites the same document.</li>
 *   <li>Order-safe: the event carries only the id and the state is read now, so an old event cannot write old data;
 *       and the topic is keyed by video id, so the events of one video are handled one after another.</li>
 *   <li>Eventually consistent: a video appears in search about a second after it becomes playable.</li>
 * </ul>
 * Elasticsearch down: retried with back-off for 30 minutes, then parked in {@code video.changed.DLT}. A full rebuild
 * from the database (new index, alias switch) repairs anything that was missed.
 */
@Component
public class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);

    private final VideoRepository videos;
    private final StatsRepository stats;
    private final SearchIndex index;
    private final JsonMapper json;

    public SearchIndexer(VideoRepository videos, StatsRepository stats, SearchIndex index, JsonMapper json) {
        this.videos = videos;
        this.stats = stats;
        this.index = index;
        this.json = json;
    }

    @KafkaListener(id = "search-indexer", topics = Topics.VIDEO_CHANGED,
            containerFactory = "patientListenerContainerFactory")
    public void onVideoChanged(String payload) {
        sync(json.readValue(payload, VideoChangedEvent.class).videoId());
    }

    /** Only playable videos are searchable; anything else (deleted, failed) is removed from the index. */
    public void sync(long videoId) {
        Optional<VideoEntity> video = videos.findById(videoId).filter(v -> v.getStatus().isPlayable());
        if (video.isEmpty()) {
            index.delete(videoId);
            log.debug("Video {} removed from search", videoId);
            return;
        }
        VideoEntity v = video.get();
        index.upsert(videoId, new SearchIndex.VideoDocument(v.getTitle(), v.getUserId(), stats.viewCount(videoId),
                v.getPublishedAt(), v.getSourceDurationMs()));
        log.debug("Video {} indexed", videoId);
    }
}
