package dk.airport.booking.messaging;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Event names and payloads published by booking-service. Documented in docs/events.md. */
public final class BookingEvents {

    public static final String CREATED = "booking.created";
    public static final String CONFIRMED = "booking.confirmed";
    public static final String CANCELLED = "booking.cancelled";
    public static final String CHECKED_IN = "booking.checkedin";
    /** Saga compensation (dev plan DP-32): a payment arrived for a booking that was already cancelled. */
    public static final String PAYMENT_REJECTED = "booking.payment.rejected";

    private BookingEvents() {}

    public record PassengerPayload(String firstName, String lastName, String email) {}

    /** booking.payment.rejected: payment-service refunds exactly this payment. */
    public record PaymentRejectedPayload(Long bookingId, String bookingReference, BookingStatus status,
                                         Long paymentId, BigDecimal amount, String currency, String reason) {}

    public record BookingPayload(Long bookingId, String bookingReference, Long flightId, String flightNumber,
                                 OffsetDateTime departureTime, String seatNumber, BigDecimal price, String currency,
                                 BookingStatus status, PassengerPayload passenger, String reason) {}

    /** Common payload for all booking events. {@code reason} is only set for booking.cancelled. */
    public static PaymentRejectedPayload paymentRejected(Booking b, Long paymentId, BigDecimal amount,
                                                         String currency) {
        return new PaymentRejectedPayload(b.getId(), b.getBookingReference(), b.getStatus(), paymentId, amount,
                currency, "Payment arrived after the booking was cancelled ("
                + (b.getCancellationReason() == null ? "no reason recorded" : b.getCancellationReason()) + ")");
    }

    public static BookingPayload payload(Booking b, String reason) {
        return new BookingPayload(b.getId(), b.getBookingReference(), b.getFlightId(), b.getFlightNumber(),
                b.getDepartureTime(), b.getSeatNumber(), b.getPrice(), b.getCurrency(), b.getStatus(),
                new PassengerPayload(b.getPassenger().getFirstName(), b.getPassenger().getLastName(),
                        b.getPassenger().getEmail()),
                reason);
    }
}
