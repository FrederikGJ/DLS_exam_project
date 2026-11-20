package dk.airport.baggage.rest.dto;

import dk.airport.baggage.domain.BaggageType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** Body of {@code POST /api/baggage/v1/baggage}. */
public record RegisterBaggageRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6}$", message = "must be 6 alphanumeric characters")
        String bookingReference,
        @NotNull @DecimalMin("0.1") @DecimalMax("32.0")
        @Digits(integer = 3, fraction = 2, message = "must have at most 3 integer and 2 decimal digits")
        BigDecimal weightKg,
        @NotNull BaggageType type) {
}
