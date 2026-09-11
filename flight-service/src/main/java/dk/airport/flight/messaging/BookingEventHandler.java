package dk.airport.flight.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.flight.service.FlightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies booking events to seats. Idempotent: every eventId is recorded in processed_event
 * inside the same transaction as the state change.
 */
@Component
public class BookingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingEventHandler.class);

    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";

    private final FlightService flightService;
    private final ProcessedEventRepository processedEvents;

    public BookingEventHandler(FlightService flightService, ProcessedEventRepository processedEvents) {
        this.flightService = flightService;
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
            case BOOKING_CONFIRMED -> flightService.setSeatAvailability(
                    p.path("flightId").asLong(), p.path("seatNumber").asText(), false);
            case BOOKING_CANCELLED -> {
                if (p.hasNonNull("flightId") && p.hasNonNull("seatNumber")) {
                    flightService.setSeatAvailability(p.path("flightId").asLong(), p.path("seatNumber").asText(), true);
                }
            }
            default -> log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
    }
}
