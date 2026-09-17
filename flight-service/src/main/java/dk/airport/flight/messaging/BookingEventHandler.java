package dk.airport.flight.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.flight.service.FlightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Applies booking events to seats. Idempotent: every eventId is recorded in processed_event
 * inside the same transaction as the state change. Commutative (dev plan DP-31): the event's occurredAt decides,
 * so a stale event is recorded as processed but changes nothing (see Seat#applyAvailability).
 */
@Component
public class BookingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingEventHandler.class);

    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    /** Used for an envelope without occurredAt: such an event may fill in an unknown state but never override one. */
    static final OffsetDateTime UNKNOWN_TIME = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    private final FlightService flightService;
    private final ProcessedEventRepository processedEvents;

    public BookingEventHandler(FlightService flightService, ProcessedEventRepository processedEvents) {
        this.flightService = flightService;
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
        OffsetDateTime occurredAt = envelope.occurredAt() != null ? envelope.occurredAt() : UNKNOWN_TIME;
        switch (envelope.eventType()) {
            case BOOKING_CONFIRMED -> flightService.setSeatAvailability(
                    p.path("flightId").asLong(), p.path("seatNumber").asText(), false, occurredAt);
            case BOOKING_CANCELLED -> {
                if (p.hasNonNull("flightId") && p.hasNonNull("seatNumber")) {
                    flightService.setSeatAvailability(p.path("flightId").asLong(), p.path("seatNumber").asText(),
                            true, occurredAt);
                }
            }
            default -> log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
        return true;
    }
}
