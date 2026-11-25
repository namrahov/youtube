package az.youtube.worker;

import az.youtube.dto.event.VideoDeletedEvent;
import az.youtube.messaging.Topics;
import az.youtube.service.VideoLifecycleService;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Removes the files of deleted videos, and of failed videos after their retention period. */
@Component
public class StorageCleaner {

    private static final int BATCH_SIZE = 100;

    private final VideoLifecycleService lifecycle;
    private final JsonMapper json;

    public StorageCleaner(VideoLifecycleService lifecycle, JsonMapper json) {
        this.lifecycle = lifecycle;
        this.json = json;
    }

    /** Storage errors are retried with a growing pause (see KafkaConfig#patientListenerContainerFactory). */
    @KafkaListener(id = "storage-cleaner", topics = Topics.VIDEO_DELETED,
            containerFactory = "patientListenerContainerFactory")
    public void onVideoDeleted(String payload) {
        VideoDeletedEvent event = json.readValue(payload, VideoDeletedEvent.class);
        lifecycle.purgeStorage(event.videoId(), event.originalS3Key());
    }

    @Scheduled(fixedDelayString = "${youtube.lifecycle.purge-sweep-interval-ms}")
    public void purgeFailedVideos() {
        while (lifecycle.purgeFailed(BATCH_SIZE) == BATCH_SIZE) {
            // a full batch: there may be more
        }
    }
}
