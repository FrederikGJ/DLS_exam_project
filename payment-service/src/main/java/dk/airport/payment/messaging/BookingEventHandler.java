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
    /** booking-service got a payment for a booking that was already cancelled (saga compensation, DP-32). */
    public static final String BOOKING_PAYMENT_REJECTED = "booking.payment.rejected";

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
        switch (envelope.eventType()) {
            case BOOKING_CANCELLED -> {
                String reference = p.path("bookingReference").asText();
                int refunded = paymentService.refundBooking(reference);
                log.info("Booking {} cancelled -> {} payment(s) refunded", reference, refunded);
            }
            case BOOKING_PAYMENT_REJECTED -> {
                String reference = p.path("bookingReference").asText();
                boolean refunded = paymentService.refundRejectedPayment(p.path("paymentId").asLong(), reference);
                log.info("Payment {} rejected by booking {} -> refunded: {}", p.path("paymentId").asLong(),
                        reference, refunded);
            }
            default -> log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
    }
}
