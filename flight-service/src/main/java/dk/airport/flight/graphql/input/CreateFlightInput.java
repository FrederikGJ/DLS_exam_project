package dk.airport.flight.graphql.input;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CreateFlightInput(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{3,8}$", message = "flightNumber must be 3-8 alphanumeric characters") String flightNumber,
        @NotNull Long airlineId,
        @NotNull Long aircraftId,
        @NotBlank @Size(min = 3, max = 3, message = "origin must be a 3-letter IATA code") String origin,
        @NotBlank @Size(min = 3, max = 3, message = "destination must be a 3-letter IATA code") String destination,
        @NotNull OffsetDateTime scheduledDeparture,
        @NotNull OffsetDateTime scheduledArrival,
        @Size(max = 10) String gate,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal basePrice
) {}
