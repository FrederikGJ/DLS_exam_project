package dk.airport.baggage.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Publishes events wrapped in {@link EventEnvelope} - via the transactional outbox.
 * <p>
 * {@code publish} never talks to RabbitMQ. It serializes the envelope once and inserts it into
 * {@code outbox_event} inside the caller's transaction, so the event is committed atomically with the state
 * change it describes (or rolled back with it). {@link OutboxRelay} sends the rows to the broker afterwards.
 * Calling this outside a read-write transaction is a programming error and fails fast.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final String producer;

    public EventPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper,
                          @Value("${app.messaging.producer}") String producer) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.producer = producer;
    }

    public EventEnvelope publish(String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("publish(" + eventType + ") must be called inside a read-write "
                    + "transaction: the outbox row has to commit together with the state change it describes");
        }
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                producer,
                objectMapper.valueToTree(payload));
        try {
            String json = objectMapper.writeValueAsString(envelope);
            outbox.save(new OutboxEvent(envelope.eventId(), eventType, json, envelope.occurredAt()));
            log.info("Queued event {} eventId={} in outbox", eventType, envelope.eventId());
            return envelope;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event " + eventType, e);
        }
    }
}
