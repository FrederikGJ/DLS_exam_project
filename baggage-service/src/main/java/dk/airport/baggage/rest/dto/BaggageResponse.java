package dk.airport.baggage.rest.dto;

import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.domain.BaggageStatus;
import dk.airport.baggage.domain.BaggageType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A piece of baggage as returned by the REST API - the same fields as the GraphQL type {@code Baggage}. */
public record BaggageResponse(
        Long id,
        String tagNumber,
        String bookingReference,
        String passengerName,
        String flightNumber,
        BigDecimal weightKg,
        BaggageType type,
        BaggageStatus status,
        String lastLocation,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static BaggageResponse from(Baggage bag) {
        return new BaggageResponse(bag.getId(), bag.getTagNumber(), bag.getBookingReference(), bag.getPassengerName(),
                bag.getFlightNumber(), bag.getWeightKg(), bag.getType(), bag.getStatus(), bag.getLastLocation(),
                bag.getCreatedAt(), bag.getUpdatedAt());
    }
}
