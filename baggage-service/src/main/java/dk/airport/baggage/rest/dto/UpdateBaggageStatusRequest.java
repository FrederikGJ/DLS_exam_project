package dk.airport.baggage.rest.dto;

import dk.airport.baggage.domain.BaggageStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/baggage/v1/baggage/{tagNumber}/status}. A missing location keeps the previous one. */
public record UpdateBaggageStatusRequest(
        @NotNull BaggageStatus status,
        @Size(max = 100) String location) {
}
