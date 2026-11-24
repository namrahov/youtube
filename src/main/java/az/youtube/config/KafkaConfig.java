package az.youtube.config;

import az.youtube.dto.event.TranscodeRequestedEvent;
import az.youtube.messaging.Topics;
import az.youtube.service.ProcessingService;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Kafka header carrying the outbox event type. */
    public static final String EVENT_TYPE_HEADER = "event-type";

    /** Partitions per topic: the upper bound of parallel consumers (worker threads) for that topic. */
    private static final int PARTITIONS = 6;

    /** Created on startup by KafkaAdmin if missing. */
    @Bean
    public KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(
                topic(Topics.VIDEO_UPLOADED),
                topic(Topics.VIDEO_UPLOADED + Topics.DLT_SUFFIX),
                topic(Topics.TRANSCODE_360P),
                topic(Topics.TRANSCODE_720P),
                topic(Topics.TRANSCODE_1080P),
                topic(Topics.TRANSCODE_4K));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * A failed record is retried {@code max-retries} times. Then:
     * <ul>
     *   <li>a transcoding job: its variant is marked FAILED, the other variants and playback carry on;</li>
     *   <li>anything else (the planner): parked in {@code <topic>.DLT} instead of being dropped.</li>
     * </ul>
     * Unparseable payloads are not retried: retrying cannot fix them.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafka, ProcessingService processing,
                                                 TranscodingProperties properties, JsonMapper json) {
        var deadLetter = new DeadLetterPublishingRecoverer(kafka, (record, exception) ->
                new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            if (!Topics.isTranscodeTopic(record.topic())) {
                deadLetter.accept(record, exception);
                return;
            }
            TranscodeRequestedEvent job;
            try {
                job = json.readValue((String) record.value(), TranscodeRequestedEvent.class);
            } catch (JacksonException e) {
                log.error("Unreadable transcoding job dropped: {}", record.value(), e);
                return;
            }
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
            processing.failVariant(job.videoId(), job.resolution(), cause.getMessage());
        };
        var handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(properties.retryInterval().toMillis(), properties.maxRetries()));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
