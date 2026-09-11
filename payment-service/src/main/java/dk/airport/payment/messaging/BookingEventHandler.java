package dk.airport.payment.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies booking events to payments. Idempotent: every eventId is recorded in processed_event
 * inside the same transaction as the state change.
 */
@Component
public class BookingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingEventHandler.class);

    public static final String BOOKING_CANCELLED = "booking.cancelled";

    private final PaymentService paymentService;
    private final ProcessedEventRepository processedEvents;

    public BookingEventHandler(PaymentService paymentService, ProcessedEventRepository processedEvents) {
        this.paymentService = paymentService;
        this.processedEvents = processedEvents;
    }

    @Transactional
    public void handle(EventEnvelope envelope) {
        if (processedEvents.existsById(envelope.eventId())) {
            log.info("Skipping already processed event {} eventId={}", envelope.eventType(), envelope.eventId());
            return;
        }
        JsonNode p = envelope.payload();
        if (BOOKING_CANCELLED.equals(envelope.eventType())) {
            String reference = p.path("bookingReference").asText();
            int refunded = paymentService.refundBooking(reference);
            log.info("Booking {} cancelled -> {} payment(s) refunded", reference, refunded);
        } else {
            log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
    }
}
