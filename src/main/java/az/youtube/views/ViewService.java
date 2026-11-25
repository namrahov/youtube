package az.youtube.views;

import az.youtube.config.ViewsProperties;
import az.youtube.dto.event.ViewEvent;
import az.youtube.dto.request.ViewRequest;
import az.youtube.entity.VideoEntity;
import az.youtube.messaging.Topics;
import az.youtube.repository.VideoRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write path of a view: check it, put it on Kafka, answer 202. Nothing is counted here and no database row is
 * written: at tens of thousands of views a second, one write per view is exactly what must be avoided. Counting,
 * deduplication and the database update happen in batches in {@link ViewAggregator}.
 * <p>
 * Not through the outbox: the outbox exists to make a database change and its event atomic. A view changes nothing
 * in the database, so an outbox row per view would only add the write we are avoiding. If Kafka does not
 * acknowledge, the client gets 503 and retries with the same eventId.
 */
@Service
public class ViewService {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final VideoRepository videos;
    private final KafkaTemplate<String, String> kafka;
    private final ViewsProperties properties;
    private final JsonMapper json;

    public ViewService(VideoRepository videos, KafkaTemplate<String, String> kafka, ViewsProperties properties,
                       JsonMapper json) {
        this.videos = videos;
        this.kafka = kafka;
        this.properties = properties;
        this.json = json;
    }

    /**
     * The video lookup is a primary-key read; at real scale "is it playable, how long is it" would be cached per
     * video (it hardly ever changes) so the hot path does not touch the database at all.
     */
    public void record(long userId, long videoId, ViewRequest request) {
        VideoEntity video = videos.findById(videoId)
                .filter(v -> v.getStatus().isPlayable())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        Duration required = properties.requiredWatch(video.getSourceDurationMs());
        if (Duration.ofSeconds(request.watchedSeconds()).compareTo(required) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A view counts after " + required.toSeconds() + " s of watching");
        }
        ViewEvent event = new ViewEvent(request.eventId(), videoId, userId, Instant.now());
        try {
            kafka.send(Topics.VIDEO_VIEWS, String.valueOf(videoId), json.writeValueAsString(event))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    private static ResponseStatusException unavailable(Exception cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "View not recorded, retry", cause);
    }
}
