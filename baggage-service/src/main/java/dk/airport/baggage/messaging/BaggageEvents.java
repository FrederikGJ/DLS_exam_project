package dk.airport.baggage.messaging;

import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.domain.BaggageStatus;
import dk.airport.baggage.domain.BaggageType;

import java.math.BigDecimal;

/** Event names and payloads published by baggage-service. Documented in docs/events.md. */
public final class BaggageEvents {

    public static final String REGISTERED = "baggage.registered";
    public static final String STATUS_CHANGED = "baggage.status.changed";

    private BaggageEvents() {}

    public record Registered(String tagNumber, String bookingReference, String passengerName, String flightNumber,
                             BigDecimal weightKg, BaggageType type, BaggageStatus status) {}

    public record StatusChanged(String tagNumber, String bookingReference, String flightNumber,
                                BaggageStatus oldStatus, BaggageStatus newStatus, String location) {}

    public static Registered registered(Baggage b) {
        return new Registered(b.getTagNumber(), b.getBookingReference(), b.getPassengerName(), b.getFlightNumber(),
                b.getWeightKg(), b.getType(), b.getStatus());
    }

    public static StatusChanged statusChanged(Baggage b, BaggageStatus old, String location) {
        return new StatusChanged(b.getTagNumber(), b.getBookingReference(), b.getFlightNumber(), old, b.getStatus(),
                location != null && !location.isBlank() ? location.trim() : b.getLastLocation());
    }
}
