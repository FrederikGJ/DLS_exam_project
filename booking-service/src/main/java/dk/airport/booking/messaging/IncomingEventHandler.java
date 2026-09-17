package dk.airport.booking.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.booking.query.BookingOverviewProjector;
import dk.airport.booking.query.OverviewBaggage;
import dk.airport.booking.query.OverviewPayment;
import dk.airport.booking.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Applies payment.*, flight.* and baggage.* events to bookings and to the read model booking_overview. Idempotent:
 * every eventId is recorded in processed_event inside the same transaction as the state change (and the events it
 * publishes).
 */
@Component
public class IncomingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(IncomingEventHandler.class);

    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";
    public static final String FLIGHT_CANCELLED = "flight.cancelled";
    public static final String FLIGHT_STATUS_CHANGED = "flight.status.changed";
    public static final String FLIGHT_GATE_CHANGED = "flight.gate.changed";
    public static final String BAGGAGE_REGISTERED = "baggage.registered";
    public static final String BAGGAGE_STATUS_CHANGED = "baggage.status.changed";
    static final OffsetDateTime UNKNOWN_TIME = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    private final BookingService bookingService;
    private final BookingOverviewProjector overview;
    private final ProcessedEventRepository processedEvents;

    public IncomingEventHandler(BookingService bookingService, BookingOverviewProjector overview,
                                ProcessedEventRepository processedEvents) {
        this.bookingService = bookingService;
        this.overview = overview;
        this.processedEvents = processedEvents;
    }

    /** Applies one event; returns false if it was a duplicate (eventId already in processed_event) and was skipped. */
    @Transactional
    public boolean handle(EventEnvelope envelope) {
        if (processedEvents.existsById(envelope.eventId())) {
            log.info("Skipping already processed event {} eventId={}", envelope.eventType(), envelope.eventId());
            return false;
        }
        JsonNode p = envelope.payload();
        // the producer's clock decides which of two events about the same thing is newer (dev plan DP-31); an event
        // without occurredAt may fill in unknown state but never override known state
        OffsetDateTime at = envelope.occurredAt() != null ? envelope.occurredAt() : UNKNOWN_TIME;
        // the read model is projected before the booking changes, so every transaction locks the overview row before
        // the booking row - the same order as the commands in BookingService (no deadlock between the two)
        switch (envelope.eventType()) {
            case PAYMENT_COMPLETED -> {
                overview.onPayment(text(p, "bookingReference"), payment(p, "COMPLETED", at));
                bookingService.onPaymentCompleted(p.path("bookingReference").asText(),
                        p.hasNonNull("paymentId") ? p.get("paymentId").asLong() : null, decimal(p, "amount"),
                        text(p, "currency"));
            }
            case PAYMENT_FAILED -> {
                overview.onPayment(text(p, "bookingReference"), payment(p, "FAILED", at));
                bookingService.onPaymentFailed(p.path("bookingReference").asText(), text(p, "failureReason"));
            }
            case PAYMENT_REFUNDED -> overview.onPayment(text(p, "bookingReference"), payment(p, "REFUNDED", at));
            case FLIGHT_CANCELLED -> bookingService.onFlightCancelled(p.path("flightId").asLong(), at);
            case FLIGHT_STATUS_CHANGED -> bookingService.onFlightSnapshotChanged(p.path("flightId").asLong(),
                    text(p, "newStatus"), text(p, "gate"), at);
            case FLIGHT_GATE_CHANGED -> bookingService.onFlightSnapshotChanged(p.path("flightId").asLong(),
                    null, text(p, "newGate"), at);
            case BAGGAGE_REGISTERED -> overview.onBaggage(text(p, "bookingReference"), new OverviewBaggage(
                    text(p, "tagNumber"), text(p, "type"), decimal(p, "weightKg"),
                    text(p, "status"), text(p, "lastLocation"), at, at));
            case BAGGAGE_STATUS_CHANGED -> overview.onBaggage(text(p, "bookingReference"), new OverviewBaggage(
                    text(p, "tagNumber"), null, null, text(p, "newStatus"), text(p, "location"), null, at));
            default -> log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
        return true;
    }

    /** The payment as this event describes it; {@code status} follows from the event type. */
    private static OverviewPayment payment(JsonNode p, String status, OffsetDateTime at) {
        return new OverviewPayment(p.path("paymentId").asLong(), status, decimal(p, "amount"), text(p, "currency"),
                text(p, "cardLast4"), text(p, "failureReason"), "REFUNDED".equals(status) ? null : at, at);
    }

    private static String text(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).asText() : null;
    }

    private static BigDecimal decimal(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).decimalValue() : null;
    }
}
