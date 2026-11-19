package dk.airport.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Listens on the "baggage.#" queue. Parses the envelope and hands it to the transactional handler.
 * Failures are retried (3 attempts, see application.yml) and then dead-lettered to booking-service.dlq.
 */
@Component
public class BaggageEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(BaggageEventsConsumer.class);

    private final EventMetrics metrics;
    private final ObjectMapper objectMapper;
    private final IncomingEventHandler handler;

    public BaggageEventsConsumer(ObjectMapper objectMapper, IncomingEventHandler handler, EventMetrics metrics) {
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @RabbitListener(queues = "${app.messaging.queues.baggage-events}")
    public void onMessage(Message message) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
            if (envelope == null) {
                throw new IOException("Missing event envelope");
            }
        } catch (IOException e) {
            metrics.consumed(EventMetrics.UNREADABLE, EventMetrics.FAILED);
            throw new UncheckedIOException("Malformed event envelope", e);
        }
        MDC.put("eventId", envelope.eventId());
        MDC.put("eventType", envelope.eventType());
        try {
            log.info("Received event {} eventId={}", envelope.eventType(), envelope.eventId());
            boolean processed = handler.handle(envelope);
            metrics.consumed(envelope.eventType(), processed ? EventMetrics.PROCESSED : EventMetrics.DUPLICATE);
        } catch (RuntimeException e) {
            metrics.consumed(envelope.eventType(), EventMetrics.FAILED);
            throw e;
        } finally {
            MDC.remove("eventId");
            MDC.remove("eventType");
        }
    }
}
