package az.youtube.worker;

import az.youtube.config.KafkaConfig;
import az.youtube.repository.OutboxRepository;
import az.youtube.repository.OutboxRepository.OutboxMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves committed outbox rows to Kafka: claim a batch (one UPDATE), send it with no transaction open, then mark the
 * acked rows published and give the failed ones back. While Kafka is down the rows simply wait.
 * Delivery is at-least-once (a crash after the send but before the mark sends again), so every consumer is
 * idempotent: the planner and the workers change a row only if it is still in the expected status.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    /** Must comfortably exceed the time to send a batch, or another instance could claim it again meanwhile. */
    private static final int CLAIM_SECONDS = 30;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${youtube.outbox.poll-interval-ms:500}")
    public void publishPending() {
        List<OutboxMessage> batch = outbox.claim(BATCH_SIZE, CLAIM_SECONDS);
        if (batch.isEmpty()) {
            return;
        }

        // Send everything first, then wait: one network round trip per batch instead of one per event.
        List<CompletableFuture<SendResult<String, String>>> acks = new ArrayList<>(batch.size());
        for (OutboxMessage message : batch) {
            CompletableFuture<SendResult<String, String>> ack = send(message);
            acks.add(ack);
            if (ack.isCompletedExceptionally()) {
                // The broker is unreachable (send() blocked for max.block.ms): fail the rest now, retry later.
                while (acks.size() < batch.size()) {
                    acks.add(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
                }
                break;
            }
        }

        List<Long> published = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEND_TIMEOUT_SECONDS);
        for (int i = 0; i < batch.size(); i++) {
            long id = batch.get(i).id();
            try {
                acks.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                published.add(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.add(id);
            } catch (Exception e) {
                failed.add(id);
            }
        }

        outbox.markPublished(published);
        outbox.release(failed);
        if (!failed.isEmpty()) {
            log.warn("{} of {} outbox events not published, will retry", failed.size(), batch.size());
        }
    }

    private CompletableFuture<SendResult<String, String>> send(OutboxMessage message) {
        var record = new ProducerRecord<>(message.topic(), message.kafkaKey(), message.payload());
        record.headers().add(KafkaConfig.EVENT_TYPE_HEADER, message.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            return kafka.send(record);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
