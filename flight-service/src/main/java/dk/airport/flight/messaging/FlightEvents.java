package dk.airport.flight.messaging;

import dk.airport.flight.domain.Flight;
import dk.airport.flight.domain.FlightStatus;

import java.time.OffsetDateTime;

/** Event names and payloads published by flight-service. Documented in docs/events.md. */
public final class FlightEvents {

    public static final String CREATED = "flight.created";
    public static final String STATUS_CHANGED = "flight.status.changed";
    public static final String GATE_CHANGED = "flight.gate.changed";
    public static final String CANCELLED = "flight.cancelled";

    private FlightEvents() {}

    public record Created(Long flightId, String flightNumber, String airlineCode, String origin, String destination,
                          OffsetDateTime scheduledDeparture, OffsetDateTime scheduledArrival, String gate,
                          FlightStatus status) {}

    public record StatusChanged(Long flightId, String flightNumber, FlightStatus oldStatus, FlightStatus newStatus,
                                OffsetDateTime scheduledDeparture, String gate) {}

    public record GateChanged(Long flightId, String flightNumber, String oldGate, String newGate,
                              OffsetDateTime scheduledDeparture) {}

    public record Cancelled(Long flightId, String flightNumber, OffsetDateTime scheduledDeparture, String reason) {}

    public static Created created(Flight f) {
        return new Created(f.getId(), f.getFlightNumber(), f.getAirline().getIataCode(), f.getOrigin(), f.getDestination(),
                f.getScheduledDeparture(), f.getScheduledArrival(), f.getGate(), f.getStatus());
    }

    public static StatusChanged statusChanged(Flight f, FlightStatus old) {
        return new StatusChanged(f.getId(), f.getFlightNumber(), old, f.getStatus(), f.getScheduledDeparture(), f.getGate());
    }

    public static GateChanged gateChanged(Flight f, String oldGate) {
        return new GateChanged(f.getId(), f.getFlightNumber(), oldGate, f.getGate(), f.getScheduledDeparture());
    }

    public static Cancelled cancelled(Flight f) {
        return new Cancelled(f.getId(), f.getFlightNumber(), f.getScheduledDeparture(), "Flight cancelled by airline");
    }
}
