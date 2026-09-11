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
        cancelled.cancel();
        assertThatThrownBy(cancelled::checkIn).isInstanceOf(ApiException.class);
    }

    @Test
    void anyActiveStatusCanBeCancelledButNotTwice() {
        Booking pending = newBooking();
        pending.cancel();
        assertThat(pending.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        Booking confirmed = newBooking();
        confirmed.confirm();
        confirmed.cancel();
        assertThat(confirmed.isCancelled()).isTrue();

        Booking checkedIn = newBooking();
        checkedIn.confirm();
        checkedIn.checkIn();
        checkedIn.cancel();
        assertThat(checkedIn.isCancelled()).isTrue();

        assertThatThrownBy(pending::cancel).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void snapshotUpdateOnlyTouchesChangedFields() {
        Booking b = newBooking();
        b.updateFlightSnapshot(null, "B7");
        assertThat(b.getGate()).isEqualTo("B7");
        assertThat(b.getFlightStatus()).isEqualTo("SCHEDULED");
        b.updateFlightSnapshot("DELAYED", null);
        assertThat(b.getFlightStatus()).isEqualTo("DELAYED");
        assertThat(b.getGate()).isEqualTo("B7");
    }
}
