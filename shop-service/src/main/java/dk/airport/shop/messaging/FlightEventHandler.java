package dk.airport.shop.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * shop-service has no state that depends on flight events; gate changes are logged so the
 * navigation staff can see them. Idempotent via processed_event.
 */
@Component
public class FlightEventHandler {

    private static final Logger log = LoggerFactory.getLogger(FlightEventHandler.class);

    public static final String FLIGHT_GATE_CHANGED = "flight.gate.changed";

    private final ProcessedEventRepository processedEvents;

    public FlightEventHandler(ProcessedEventRepository processedEvents) {
        this.processedEvents = processedEvents;
    }

    @Transactional
    public void handle(EventEnvelope envelope) {
        if (processedEvents.existsById(envelope.eventId())) {
            log.info("Skipping already processed event {} eventId={}", envelope.eventType(), envelope.eventId());
            return;
        }
        JsonNode p = envelope.payload();
        if (FLIGHT_GATE_CHANGED.equals(envelope.eventType())) {
            log.info("Gate change for flight {} : {} -> {} (eventId {})",
                    p.path("flightNumber").asText(), p.path("oldGate").asText(null), p.path("newGate").asText(null),
                    envelope.eventId());
        } else {
            log.debug("Ignoring event type {}", envelope.eventType());
        }
        processedEvents.save(new ProcessedEvent(envelope.eventId(), envelope.eventType()));
    }
}
