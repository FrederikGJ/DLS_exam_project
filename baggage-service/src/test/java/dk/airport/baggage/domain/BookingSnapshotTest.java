package dk.airport.baggage.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The booking snapshot only moves forward through the booking lifecycle (dev plan DP-31). */
class BookingSnapshotTest {

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-17T10:00:00Z");

    @Test
    void lateConfirmedDoesNotReopenACancelledBooking() {
        BookingSnapshot snapshot = new BookingSnapshot("K7Q2ZP", "Anna Jensen", "SK1501", 1L, "CANCELLED",
                T1.plusSeconds(10));

        assertThat(snapshot.apply("Anna Jensen", "SK1501", 1L, "CONFIRMED", T1)).isFalse();

        assertThat(snapshot.getStatus()).isEqualTo("CANCELLED");
        assertThat(snapshot.getEventOccurredAt()).isEqualTo(T1.plusSeconds(10));
    }

    @Test
    void statusMovesForwardAndTheSameStatusAgainIsANoOp() {
        BookingSnapshot snapshot = new BookingSnapshot("K7Q2ZP", "Anna Jensen", "SK1501", 1L, "PENDING_PAYMENT", T1);

        assertThat(snapshot.apply(null, null, null, "CHECKED_IN", T1.plusSeconds(2))).isTrue();
        assertThat(snapshot.apply(null, null, null, "CHECKED_IN", T1.plusSeconds(2))).isTrue();
        assertThat(snapshot.apply(null, null, null, "CONFIRMED", T1.plusSeconds(1))).isFalse();

        assertThat(snapshot.getStatus()).isEqualTo("CHECKED_IN");
        assertThat(snapshot.getPassengerName()).isEqualTo("Anna Jensen");
    }

    @Test
    void flightCancellationAlwaysApplies() {
        BookingSnapshot snapshot = new BookingSnapshot("K7Q2ZP", "Anna Jensen", "SK1501", 1L, "CHECKED_IN",
                T1.plusSeconds(30));

        snapshot.cancel(T1);

        assertThat(snapshot.getStatus()).isEqualTo("CANCELLED");
        assertThat(snapshot.getEventOccurredAt()).isEqualTo(T1.plusSeconds(30));
    }
}
