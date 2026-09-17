package dk.airport.booking.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingStateTest {

    private Booking newBooking() {
        Passenger p = new Passenger("Anna", "Jensen", "anna@example.com", "P1234567", null);
        return new Booking("K7Q2ZP", p, 1L, "SK1501", OffsetDateTime.now().plusDays(1), "A12", "SCHEDULED",
                "12C", new BigDecimal("899.00"), "DKK");
    }

    @Test
    void newBookingIsPendingPayment() {
        Booking b = newBooking();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(b.getCurrency()).isEqualTo("DKK");
        assertThat(b.getCreatedAt()).isEqualTo(b.getUpdatedAt());
    }

    @Test
    void pendingCanBeConfirmedThenCheckedIn() {
        Booking b = newBooking();
        b.confirm();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        b.checkIn();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(b.getUpdatedAt()).isAfterOrEqualTo(b.getCreatedAt());
    }

    @Test
    void confirmIsOnlyAllowedFromPendingPayment() {
        Booking b = newBooking();
        b.confirm();
        assertThatThrownBy(b::confirm).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void checkInRequiresConfirmed() {
        Booking pending = newBooking();
        assertThatThrownBy(pending::checkIn).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_STATE);

        Booking cancelled = newBooking();
        cancelled.cancel("Cancelled by passenger");
        assertThatThrownBy(cancelled::checkIn).isInstanceOf(ApiException.class);
    }

    @Test
    void anyActiveStatusCanBeCancelledButNotTwice() {
        Booking pending = newBooking();
        pending.cancel("Payment failed");
        assertThat(pending.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(pending.getCancellationReason()).isEqualTo("Payment failed");

        Booking confirmed = newBooking();
        confirmed.confirm();
        confirmed.cancel("Flight cancelled");
        assertThat(confirmed.isCancelled()).isTrue();

        Booking checkedIn = newBooking();
        checkedIn.confirm();
        checkedIn.checkIn();
        checkedIn.cancel("Cancelled by passenger");
        assertThat(checkedIn.isCancelled()).isTrue();

        assertThatThrownBy(() -> pending.cancel("again")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void snapshotUpdateOnlyTouchesChangedFields() {
        Booking b = newBooking();
        assertThat(b.applyFlightSnapshot(null, "B7", T1)).isTrue();
        assertThat(b.getGate()).isEqualTo("B7");
        assertThat(b.getFlightStatus()).isEqualTo("SCHEDULED");
        assertThat(b.applyFlightSnapshot("DELAYED", null, T2)).isTrue();
        assertThat(b.getFlightStatus()).isEqualTo("DELAYED");
        assertThat(b.getGate()).isEqualTo("B7");
    }

    // ------------------------------------------------------------ event order (dev plan DP-31)

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-17T10:00:00Z");
    static final OffsetDateTime T2 = T1.plusMinutes(1);
    static final OffsetDateTime T3 = T1.plusMinutes(2);

    @Test
    void olderGateFromALateStatusEventDoesNotUndoANewerGateChange() {
        Booking b = newBooking();
        b.applyFlightSnapshot(null, "B17", T2);                         // flight.gate.changed at T2
        assertThat(b.applyFlightSnapshot("DELAYED", "B15", T1)).isTrue();   // flight.status.changed at T1, old gate

        assertThat(b.getFlightStatus()).isEqualTo("DELAYED");            // status was news
        assertThat(b.getGate()).isEqualTo("B17");                        // gate was not
        assertThat(b.getGateChangedAt()).isEqualTo(T2);
    }

    @Test
    void lateStatusEventIsIgnored() {
        Booking b = newBooking();
        b.applyFlightSnapshot("BOARDING", null, T3);

        assertThat(b.applyFlightSnapshot("DELAYED", null, T2)).isFalse();
        assertThat(b.getFlightStatus()).isEqualTo("BOARDING");
    }

    @Test
    void cancelledFlightStaysCancelledWhateverArrivesAfterIt() {
        Booking b = newBooking();
        b.applyFlightSnapshot("DELAYED", null, T3);
        assertThat(b.applyFlightSnapshot("CANCELLED", null, T2)).isTrue();   // older, but CANCELLED is final
        assertThat(b.applyFlightSnapshot("BOARDING", null, T3.plusMinutes(5))).isFalse();

        assertThat(b.getFlightStatus()).isEqualTo("CANCELLED");
        assertThat(b.getFlightStatusChangedAt()).isEqualTo(T3.plusMinutes(5));
    }

    @Test
    void equalTimestampsGiveTheSameResultInBothOrders() {
        Booking first = newBooking();
        first.applyFlightSnapshot(null, "A1", T1);
        first.applyFlightSnapshot(null, "C3", T1);
        Booking second = newBooking();
        second.applyFlightSnapshot(null, "C3", T1);
        second.applyFlightSnapshot(null, "A1", T1);

        assertThat(first.getGate()).isEqualTo(second.getGate()).isEqualTo("C3");
    }
}
