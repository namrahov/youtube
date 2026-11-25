package az.youtube.config;

import az.youtube.dto.event.TranscodeRequestedEvent;
import az.youtube.messaging.Topics;
import az.youtube.service.ProcessingService;
import az.youtube.worker.TranscodeWorker;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
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
                topic(Topics.TRANSCODE_4K),
                topic(Topics.VIDEO_CHANGED),
                topic(Topics.VIDEO_CHANGED + Topics.DLT_SUFFIX),
                topic(Topics.VIDEO_DELETED),
                topic(Topics.VIDEO_DELETED + Topics.DLT_SUFFIX),
                topic(Topics.VIDEO_VIEWS));
    }

    /**
     * For consumers whose work must not be dropped after a short outage: the search indexer (Elasticsearch down) and
     * the storage cleaner (S3 errors). Retries back off from 1 s to 1 min for up to 30 min before the record is parked
     * in its DLT. The default handler's 3 quick retries would park every event of a one-minute outage.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> patientListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory, KafkaOperations<String, String> kafka) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2);
        backOff.setMaxInterval(60_000);
        backOff.setMaxElapsedTime(Duration.ofMinutes(30).toMillis());
        var handler = new DefaultErrorHandler(deadLetter(kafka), backOff);
        handler.addNotRetryableExceptions(JacksonException.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }

    /**
     * Batch listener for the view aggregator: each poll's records are counted in one database transaction. A failed
     * batch (database or Redis down) is retried from its first record until it succeeds: views are not dropped, and
     * the offsets stored with the counters stop a batch from being counted twice.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> viewsListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2_000, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }

    private static DeadLetterPublishingRecoverer deadLetter(KafkaOperations<String, String> kafka) {
        return new DeadLetterPublishingRecoverer(kafka, (record, exception) ->
                new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * A real 4K encode can take far longer than Kafka's default {@code max.poll.interval.ms} (5 min). A consumer that
     * does not poll within it is thrown out of its group and its job is handed to another worker while the first one
     * is still encoding. So each worker pool gets {@code job-timeout + transfer-allowance} of its resolution: the
     * encoder is killed at {@code job-timeout}, so the job always ends (and the thread polls again) before Kafka
     * gives up on it. A job is redelivered only when its worker really died.
     */
    @Bean
    public ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>>
            transcoderPollInterval(TranscodingProperties properties) {
        return container -> TranscodeWorker.resolutionOfListener(container.getListenerId()).ifPresent(resolution ->
                container.getContainerProperties().getKafkaConsumerProperties().setProperty(
                        ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                        String.valueOf(properties.maxPollInterval(resolution).toMillis())));
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
        var deadLetter = deadLetter(kafka);
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
