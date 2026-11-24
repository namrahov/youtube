package az.youtube.worker;

import az.youtube.dto.event.TranscodeRequestedEvent;
import az.youtube.messaging.Topics;
import az.youtube.service.ProcessingService;
import az.youtube.transcoding.Transcoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Four worker pools, one per resolution: each listener is its own consumer group on its own topic with its own
 * thread count, so a queue of slow 4K jobs never delays 360p (the first rendition that makes a video PLAYABLE).
 * In production each pool would be a separate deployment with its own CPU/GPU profile and autoscaling;
 * here they share one process. {@code max.poll.records=1}: a thread takes one job at a time.
 */
@Component
public class TranscodeWorker {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWorker.class);
    private static final String ONE_JOB_AT_A_TIME = "max.poll.records=1";

    private final ProcessingService processing;
    private final Transcoder transcoder;
    private final JsonMapper json;

    public TranscodeWorker(ProcessingService processing, Transcoder transcoder, JsonMapper json) {
        this.processing = processing;
        this.transcoder = transcoder;
        this.json = json;
    }

    @KafkaListener(id = "transcoder-360p", topics = Topics.TRANSCODE_360P,
            concurrency = "${youtube.transcoding.concurrency.p360}", properties = ONE_JOB_AT_A_TIME)
    public void transcode360p(String payload) {
        handle(payload);
    }

    @KafkaListener(id = "transcoder-720p", topics = Topics.TRANSCODE_720P,
            concurrency = "${youtube.transcoding.concurrency.p720}", properties = ONE_JOB_AT_A_TIME)
    public void transcode720p(String payload) {
        handle(payload);
    }

    @KafkaListener(id = "transcoder-1080p", topics = Topics.TRANSCODE_1080P,
            concurrency = "${youtube.transcoding.concurrency.p1080}", properties = ONE_JOB_AT_A_TIME)
    public void transcode1080p(String payload) {
        handle(payload);
    }

    @KafkaListener(id = "transcoder-4k", topics = Topics.TRANSCODE_4K,
            concurrency = "${youtube.transcoding.concurrency.p2160}", properties = ONE_JOB_AT_A_TIME)
    public void transcode4k(String payload) {
        handle(payload);
    }

    /**
     * Claim → transcode → complete. A duplicate job (variant already final) is skipped at the claim. An exception
     * is retried by the error handler and, after the last retry, marks the variant FAILED (see KafkaConfig).
     */
    private void handle(String payload) {
        TranscodeRequestedEvent job = json.readValue(payload, TranscodeRequestedEvent.class);
        if (!processing.claimVariant(job.videoId(), job.resolution())) {
            log.debug("Job {} of video {} already done, skipped", job.resolution().label(), job.videoId());
            return;
        }
        String playlistKey = transcoder.transcode(job.videoId(), job.sourceS3Key(), job.resolution());
        processing.completeVariant(job.videoId(), job.resolution(), playlistKey);
        log.info("Video {}: {} ready", job.videoId(), job.resolution().label());
    }
}
