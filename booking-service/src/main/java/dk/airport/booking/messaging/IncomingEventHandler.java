package dk.airport.booking.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.booking.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies payment.* and flight.* events to bookings. Idempotent: every eventId is recorded in
 * processed_event inside the same transaction as the state change (and the events it publishes).
 */
@Component
public class IncomingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(IncomingEventHandler.class);

    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String FLIGHT_CANCELLED = "flight.cancelled";
    public static final String FLIGHT_STATUS_CHANGED = "flight.status.changed";
    public static final String FLIGHT_GATE_CHANGED = "flight.gate.changed";

    private final BookingService bookingService;
    private final ProcessedEventRepository processedEvents;

    public IncomingEventHandler(BookingService bookingService, ProcessedEventRepository processedEvents) {
        this.bookingService = bookingService;
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
            case PAYMENT_COMPLETED -> bookingService.onPaymentCompleted(p.path("bookingReference").asText());
            case PAYMENT_FAILED -> bookingService.onPaymentFailed(p.path("bookingReference").asText(),
                    p.hasNonNull("failureReason") ? p.get("failureReason").asText() : null);
            case FLIGHT_CANCELLED -> bookingService.onFlightCancelled(p.path("flightId").asLong());
            case FLIGHT_STATUS_CHANGED -> bookingService.onFlightSnapshotChanged(p.path("flightId").asLong(),
                    p.hasNonNull("newStatus") ? p.get("newStatus").asText() : null,
                    p.hasNonNull("gate") ? p.get("gate").asText() : null);
            case FLIGHT_GATE_CHANGED -> bookingService.onFlightSnapshotChanged(p.path("flightId").asLong(),
                    null, p.hasNonNull("newGate") ? p.get("newGate").asText() : null);
            default -> log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
    }
}
