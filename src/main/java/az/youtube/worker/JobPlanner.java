package az.youtube.worker;

import az.youtube.dto.event.VideoUploadedEvent;
import az.youtube.messaging.Topics;
import az.youtube.service.ProcessingService;
import az.youtube.transcoding.InvalidMediaException;
import az.youtube.transcoding.MediaProbe;
import az.youtube.transcoding.SourceInfo;

import java.util.Optional;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * VideoUploaded → probe the original → one transcoding job per resolution worth encoding
 * (see {@link ProcessingService#planJobs}). The probe runs outside any transaction: it is a network call and a
 * process. A file that is not a video is rejected at once; other probe errors are retried, then parked in the DLT.
 */
@Component
public class JobPlanner {

    private final ProcessingService processing;
    private final MediaProbe probe;
    private final JsonMapper json;

    public JobPlanner(ProcessingService processing, MediaProbe probe, JsonMapper json) {
        this.processing = processing;
        this.probe = probe;
        this.json = json;
    }

    @KafkaListener(id = "job-planner", topics = Topics.VIDEO_UPLOADED)
    public void onVideoUploaded(String payload) {
        VideoUploadedEvent event = json.readValue(payload, VideoUploadedEvent.class);
        if (!processing.isAwaitingPlanning(event.videoId())) {
            return;
        }
        Optional<SourceInfo> source;
        try {
            source = probe.probe(event.originalS3Key());
        } catch (InvalidMediaException e) {
            processing.rejectSource(event.videoId(), e.getMessage());
            return;
        }
        processing.planJobs(event, source);
    }
}
