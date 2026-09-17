package dk.airport.flight.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * <p>
 * The row also gets the trace context of the current span ({@code traceparent}, dev plan DP-33), so the event carries
 * the trace of the request or event that caused it across the outbox to its consumers.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final String producer;
    private final ObjectProvider<Tracer> tracer;

    public EventPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper,
                          @Value("${app.messaging.producer}") String producer, ObjectProvider<Tracer> tracer) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.producer = producer;
        this.tracer = tracer;
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
            outbox.save(new OutboxEvent(envelope.eventId(), eventType, json, envelope.occurredAt(),
                    currentTraceparent()));
            log.info("Queued event {} eventId={} in outbox", eventType, envelope.eventId());
            return envelope;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event " + eventType, e);
        }
    }

    /**
     * The current span as a W3C {@code traceparent} ({@code 00-<32 hex trace id>-<16 hex span id>-<flags>}), or null
     * when no span is active (tracing switched off, as in most tests).
     */
    private String currentTraceparent() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        TraceContext context = span.context();
        if (context.traceId().length() != 32 || context.spanId().length() != 16) {
            return null;
        }
        return "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (Boolean.FALSE.equals(context.sampled()) ? "00" : "01");
    }
}
