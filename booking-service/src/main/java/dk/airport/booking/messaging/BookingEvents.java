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

    private BookingEvents() {}

    public record PassengerPayload(String firstName, String lastName, String email) {}

    public record BookingPayload(Long bookingId, String bookingReference, Long flightId, String flightNumber,
                                 OffsetDateTime departureTime, String seatNumber, BigDecimal price, String currency,
                                 BookingStatus status, PassengerPayload passenger, String reason) {}

    /** Common payload for all booking events. {@code reason} is only set for booking.cancelled. */
    public static BookingPayload payload(Booking b, String reason) {
        return new BookingPayload(b.getId(), b.getBookingReference(), b.getFlightId(), b.getFlightNumber(),
                b.getDepartureTime(), b.getSeatNumber(), b.getPrice(), b.getCurrency(), b.getStatus(),
                new PassengerPayload(b.getPassenger().getFirstName(), b.getPassenger().getLastName(), b.getPassenger().getEmail()),
                reason);
    }
}
