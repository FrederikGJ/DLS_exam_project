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
 * Listens on the "flight.#" queue. Parses the envelope and hands it to the transactional handler.
 * Failures are retried (3 attempts, see application.yml) and then dead-lettered to booking-service.dlq.
 */
@Component
public class FlightEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(FlightEventsConsumer.class);

    private final ObjectMapper objectMapper;
    private final IncomingEventHandler handler;

    public FlightEventsConsumer(ObjectMapper objectMapper, IncomingEventHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @RabbitListener(queues = "${app.messaging.queues.flight-events}")
    public void onMessage(Message message) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed event envelope", e);
        }
        MDC.put("eventId", envelope.eventId());
        MDC.put("eventType", envelope.eventType());
        try {
            log.info("Received event {} eventId={}", envelope.eventType(), envelope.eventId());
            handler.handle(envelope);
        } finally {
            MDC.remove("eventId");
            MDC.remove("eventType");
        }
    }
}
