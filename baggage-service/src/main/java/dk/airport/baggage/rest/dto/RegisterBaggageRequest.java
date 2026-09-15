package dk.airport.baggage.rest.dto;

import dk.airport.baggage.domain.BaggageType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/baggage/v1/baggage}. Only the shape is validated here (400); the business rules
 * (0 &lt; weight &lt;= 32 kg, max 3 CHECKED bags, booking must be confirmed) live in the service and answer 422/409.
 */
public record RegisterBaggageRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6}$", message = "must be 6 alphanumeric characters")
        String bookingReference,
        @NotNull @Digits(integer = 3, fraction = 2, message = "must have at most 3 integer and 2 decimal digits")
        BigDecimal weightKg,
        @NotNull BaggageType type) {
}
