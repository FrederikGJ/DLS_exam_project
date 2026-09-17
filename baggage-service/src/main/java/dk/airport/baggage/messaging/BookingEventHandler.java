package dk.airport.baggage.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.baggage.service.BookingSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Applies booking events to the local booking_snapshot table. Idempotent: every eventId is recorded
 * in processed_event inside the same transaction as the state change. Commutative (dev plan DP-31): a late event
 * is recorded as processed but cannot move the snapshot back (see BookingSnapshot#apply).
 */
@Component
public class BookingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingEventHandler.class);

    public static final String BOOKING_CREATED = "booking.created";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String BOOKING_CHECKED_IN = "booking.checkedin";
    /** Used for an envelope without occurredAt: such an event may fill in an unknown state but never override one. */
    static final OffsetDateTime UNKNOWN_TIME = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    private final BookingSnapshotService snapshots;
    private final ProcessedEventRepository processedEvents;

    public BookingEventHandler(BookingSnapshotService snapshots, ProcessedEventRepository processedEvents) {
        this.snapshots = snapshots;
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
        switch (envelope.eventType()) {
            case BOOKING_CREATED, BOOKING_CONFIRMED, BOOKING_CANCELLED, BOOKING_CHECKED_IN -> {
                String status = p.hasNonNull("status")
                        ? p.get("status").asText()
                        : statusFromType(envelope.eventType());
                snapshots.upsert(
                        p.path("bookingReference").asText(),
                        passengerName(p.path("passenger")),
                        p.path("flightNumber").asText(),
                        p.hasNonNull("flightId") ? p.get("flightId").asLong() : null,
                        status,
                        envelope.occurredAt() != null ? envelope.occurredAt() : UNKNOWN_TIME);
            }
            default -> log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
        return true;
    }

    private static String statusFromType(String eventType) {
        return switch (eventType) {
            case BOOKING_CONFIRMED -> "CONFIRMED";
            case BOOKING_CANCELLED -> "CANCELLED";
            case BOOKING_CHECKED_IN -> "CHECKED_IN";
            default -> "PENDING_PAYMENT";
        };
    }

    private static String passengerName(JsonNode passenger) {
        if (passenger == null || passenger.isMissingNode() || passenger.isNull()) {
            return "Unknown passenger";
        }
        String name = (passenger.path("firstName").asText("") + " " + passenger.path("lastName").asText("")).trim();
        return name.isBlank() ? "Unknown passenger" : name;
    }
}
