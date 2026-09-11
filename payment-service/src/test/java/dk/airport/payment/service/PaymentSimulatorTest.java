package dk.airport.payment.service;

import dk.airport.payment.domain.ApiException;
import dk.airport.payment.domain.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentSimulatorTest {

    // "today" is fixed to 2026-09-11
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
    private final PaymentSimulator simulator = new PaymentSimulator(clock);

    @Test
    void validCardIsApproved() {
        PaymentSimulator.Result r = simulator.evaluate("4242 4242 4242 4242", "12/28");
        assertThat(r.approved()).isTrue();
        assertThat(r.failureReason()).isNull();
    }

    @Test
    void cardEndingInFourZerosHasInsufficientFunds() {
        PaymentSimulator.Result r = simulator.evaluate("4111111111110000", "12/28");
        assertThat(r.approved()).isFalse();
        assertThat(r.failureReason()).isEqualTo("Insufficient funds");
    }

    @Test
    void expiredCardIsDeclined() {
        PaymentSimulator.Result r = simulator.evaluate("4242424242424242", "08/26");
        assertThat(r.approved()).isFalse();
        assertThat(r.failureReason()).isEqualTo("Card expired");
    }

    @Test
    void cardExpiringThisMonthIsStillValid() {
        assertThat(simulator.evaluate("4242424242424242", "09/26").approved()).isTrue();
    }

    @Test
    void fourDigitYearIsAccepted() {
        assertThat(simulator.evaluate("4242424242424242", "01/2030").approved()).isTrue();
        assertThat(simulator.evaluate("4242424242424242", "01/2020").failureReason()).isEqualTo("Card expired");
    }

    @Test
    void expiryWinsOverInsufficientFunds() {
        assertThat(simulator.evaluate("4111111111110000", "01/20").failureReason()).isEqualTo("Card expired");
    }

    @Test
    void malformedExpiryIsValidationError() {
        assertThatThrownBy(() -> simulator.evaluate("4242424242424242", "13/28"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> simulator.evaluate("4242424242424242", "2028-12"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void last4IgnoresSpaces() {
        assertThat(PaymentSimulator.cardLast4("4242 4242 4242 1234")).isEqualTo("1234");
        assertThat(PaymentSimulator.cardLast4("4242424242420000")).isEqualTo("0000");
    }
}
