package az.youtube.service;

import az.youtube.repository.OutboxRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records an event in the caller's transaction instead of sending it to Kafka. Sending directly would be a dual
 * write: the DB commit and the Kafka send can each fail on their own, leaving a video UPLOADED with no job, or a
 * job for a video whose transaction rolled back.
 */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public OutboxService(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long aggregateId, String eventType, String topic, String kafkaKey, Object event) {
        outbox.append(aggregateId, eventType, topic, kafkaKey, json.writeValueAsString(event));
    }
}
