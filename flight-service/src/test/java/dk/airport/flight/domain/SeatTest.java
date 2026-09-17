package dk.airport.flight.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Last-writer-wins rule for seat availability (dev plan DP-31). */
class SeatTest {

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-17T10:00:00Z");
    static final OffsetDateTime T2 = T1.plusSeconds(5);

    @Test
    void newerEventWinsAndOlderEventIsIgnored() {
        Seat seat = new Seat(null, "12C", SeatClass.ECONOMY);

        assertThat(seat.applyAvailability(true, T2)).isTrue();          // cancelled at T2 arrives first
        assertThat(seat.applyAvailability(false, T1)).isFalse();        // confirmed at T1 arrives late

        assertThat(seat.isAvailable()).isTrue();
        assertThat(seat.getAvailabilityChangedAt()).isEqualTo(T2);
    }

    @Test
    void firstEventAlwaysApplies() {
        Seat seat = new Seat(null, "12C", SeatClass.ECONOMY);

        assertThat(seat.applyAvailability(false, T1)).isTrue();

        assertThat(seat.isAvailable()).isFalse();
    }

    @Test
    void onATieTakenWinsInBothOrders() {
        Seat first = new Seat(null, "1A", SeatClass.BUSINESS);
        first.applyAvailability(true, T1);
        first.applyAvailability(false, T1);
        Seat second = new Seat(null, "1A", SeatClass.BUSINESS);
        second.applyAvailability(false, T1);
        second.applyAvailability(true, T1);

        assertThat(first.isAvailable()).isFalse();
        assertThat(second.isAvailable()).isFalse();
    }

    @Test
    void sameEventTwiceIsANoOp() {
        Seat seat = new Seat(null, "3F", SeatClass.ECONOMY);
        seat.applyAvailability(false, T1);

        assertThat(seat.applyAvailability(false, T1)).isTrue();
        assertThat(seat.isAvailable()).isFalse();
        assertThat(seat.getAvailabilityChangedAt()).isEqualTo(T1);
    }
}
