package dk.airport.baggage.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.baggage.service.BaggageService;
import dk.airport.baggage.service.BookingSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * flight.cancelled: baggage on the flight is sent to the return desk and the booking snapshots are cancelled.
 * All other flight.* events are ignored (but recorded as processed).
 */
@Component
public class FlightEventHandler {

    private static final Logger log = LoggerFactory.getLogger(FlightEventHandler.class);

    public static final String FLIGHT_CANCELLED = "flight.cancelled";

    private final BaggageService baggageService;
    private final BookingSnapshotService snapshots;
    private final ProcessedEventRepository processedEvents;

    public FlightEventHandler(BaggageService baggageService, BookingSnapshotService snapshots,
                              ProcessedEventRepository processedEvents) {
        this.baggageService = baggageService;
        this.snapshots = snapshots;
        this.processedEvents = processedEvents;
    }

    @Transactional
    public void handle(EventEnvelope envelope) {
        if (processedEvents.existsById(envelope.eventId())) {
            log.info("Skipping already processed event {} eventId={}", envelope.eventType(), envelope.eventId());
            return;
        }
        JsonNode p = envelope.payload();
        if (FLIGHT_CANCELLED.equals(envelope.eventType())) {
            String flightNumber = p.path("flightNumber").asText();
            Long flightId = p.hasNonNull("flightId") ? p.get("flightId").asLong() : null;
            baggageService.returnBaggageForCancelledFlight(flightNumber);
            int cancelled = snapshots.cancelForFlight(flightId, flightNumber);
            log.info("Flight {} cancelled: {} booking snapshot(s) marked CANCELLED", flightNumber, cancelled);
        } else {
            log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
    }
}
