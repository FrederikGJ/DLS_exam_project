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
 * Listens on the "payment.#" queue. Parses the envelope and hands it to the transactional handler.
 * Failures are retried (3 attempts, see application.yml) and then dead-lettered to booking-service.dlq.
 */
@Component
public class PaymentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsConsumer.class);

    private final ObjectMapper objectMapper;
    private final IncomingEventHandler handler;
    private final EventMetrics metrics;

    public PaymentEventsConsumer(ObjectMapper objectMapper, IncomingEventHandler handler, EventMetrics metrics) {
        this.objectMapper = objectMapper;
        this.handler = handler;
        this.metrics = metrics;
    }

    @RabbitListener(queues = "${app.messaging.queues.payment-events}")
    public void onMessage(Message message) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
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
            metrics.consumed(envelope.eventType(), EventMetrics.FAILED);   // each attempt; the 3rd goes to the DLQ
            throw e;
        } finally {
            MDC.remove("eventId");
            MDC.remove("eventType");
        }
    }
}
