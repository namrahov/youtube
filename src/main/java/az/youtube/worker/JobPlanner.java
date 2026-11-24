package az.youtube.worker;

import az.youtube.dto.event.VideoUploadedEvent;
import az.youtube.messaging.Topics;
import az.youtube.service.ProcessingService;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** VideoUploaded → one transcoding job per resolution (see {@link ProcessingService#planJobs}). */
@Component
public class JobPlanner {

    private final ProcessingService processing;
    private final JsonMapper json;

    public JobPlanner(ProcessingService processing, JsonMapper json) {
        this.processing = processing;
        this.json = json;
    }

    @KafkaListener(id = "job-planner", topics = Topics.VIDEO_UPLOADED)
    public void onVideoUploaded(String payload) {
        processing.planJobs(json.readValue(payload, VideoUploadedEvent.class));
    }
}
