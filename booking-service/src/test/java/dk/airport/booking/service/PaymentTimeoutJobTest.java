package dk.airport.booking.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The cancellation reason is part of booking.cancelled and is translated by the frontend (app.js). */
class PaymentTimeoutJobTest {

    @Test
    void reasonNamesTheTimeoutInWholeMinutesOrSeconds() {
        assertThat(job(Duration.ofMinutes(15)).reason()).isEqualTo("Payment not received within 15 minutes");
        assertThat(job(Duration.ofMinutes(1)).reason()).isEqualTo("Payment not received within 1 minute");
        assertThat(job(Duration.ofSeconds(30)).reason()).isEqualTo("Payment not received within 30 seconds");
        assertThat(job(Duration.ofSeconds(90)).reason()).isEqualTo("Payment not received within 90 seconds");
    }

    private static PaymentTimeoutJob job(Duration timeout) {
        return new PaymentTimeoutJob(null, null, timeout);
    }
}
