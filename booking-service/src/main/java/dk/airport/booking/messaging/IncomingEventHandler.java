package dk.airport.booking.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.booking.query.BookingOverviewProjector;
import dk.airport.booking.service.BookingCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies payment.*, baggage.* and flight.* events to the booking and its read model. Idempotent: every eventId is
 * recorded in processed_event inside the same transaction as the state change (and the events it publishes).
 */
@Component
public class IncomingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(IncomingEventHandler.class);

    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";
    public static final String BAGGAGE_REGISTERED = "baggage.registered";
    public static final String BAGGAGE_STATUS_CHANGED = "baggage.status.changed";
    public static final String FLIGHT_CANCELLED = "flight.cancelled";
    public static final String FLIGHT_STATUS_CHANGED = "flight.status.changed";
    public static final String FLIGHT_GATE_CHANGED = "flight.gate.changed";

    private final BookingCommandService commands;
    private final BookingOverviewProjector projector;
    private final ProcessedEventRepository processedEvents;

    public IncomingEventHandler(BookingCommandService commands, BookingOverviewProjector projector,
                                ProcessedEventRepository processedEvents) {
        this.commands = commands;
        this.projector = projector;
        this.processedEvents = processedEvents;
    }

    @Transactional
    public boolean handle(EventEnvelope envelope) {
        if (processedEvents.existsById(envelope.eventId())) {
            log.info("Skipping already processed event {} eventId={}", envelope.eventType(), envelope.eventId());
            return false;
        }
        JsonNode p = envelope.payload();
        switch (envelope.eventType()) {
            case PAYMENT_COMPLETED -> {
                commands.onPaymentCompleted(p.path("bookingReference").asText());
                projector.paymentObserved(envelope, "COMPLETED");
            }
            case PAYMENT_FAILED -> {
                commands.onPaymentFailed(p.path("bookingReference").asText(),
                        p.path("failureReason").asText(null));
                projector.paymentObserved(envelope, "FAILED");
            }
            case PAYMENT_REFUNDED -> projector.paymentObserved(envelope, "REFUNDED");
            case BAGGAGE_REGISTERED -> projector.baggageObserved(envelope, true);
            case BAGGAGE_STATUS_CHANGED -> projector.baggageObserved(envelope, false);
            // occurredAt from the envelope orders the flight events (ticket 10, see docs/events.md)
            case FLIGHT_CANCELLED -> commands.onFlightCancelled(p.path("flightId").asLong(), envelope.occurredAt());
            case FLIGHT_STATUS_CHANGED -> commands.onFlightSnapshotChanged(p.path("flightId").asLong(),
                    p.hasNonNull("newStatus") ? p.get("newStatus").asText() : null,
                    p.hasNonNull("gate") ? p.get("gate").asText() : null, envelope.occurredAt());
            case FLIGHT_GATE_CHANGED -> commands.onFlightSnapshotChanged(p.path("flightId").asLong(),
                    null, p.hasNonNull("newGate") ? p.get("newGate").asText() : null, envelope.occurredAt());
            default -> log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
        return true;
    }
}
