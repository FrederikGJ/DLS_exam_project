package dk.airport.payment.graphql.input;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** Arguments of the pay mutation, grouped for Bean Validation. cardNumber/cvv are never persisted or logged. */
public record PayInput(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6}$", message = "bookingReference must be 6 alphanumeric characters") String bookingReference,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^(\\d\\s?){13,19}$", message = "cardNumber must be 13-19 digits") String cardNumber,
        @NotBlank @Pattern(regexp = "^(0[1-9]|1[0-2])/\\d{2}(\\d{2})?$", message = "expiry must be in format MM/YY") String expiry,
        @NotBlank @Pattern(regexp = "^\\d{3,4}$", message = "cvv must be 3-4 digits") String cvv
) {
    @Override
    public String toString() {
        return "PayInput[bookingReference=" + bookingReference + ", amount=" + amount + ", card=****]";
    }
}
