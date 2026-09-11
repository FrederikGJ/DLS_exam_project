package dk.airport.baggage.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * Common envelope for every event on the "airport.events" exchange.
 * Every service has an identical copy of this record (no shared library, to keep
 * the services independently buildable).
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        OffsetDateTime occurredAt,
        String producer,
        JsonNode payload
) {}
