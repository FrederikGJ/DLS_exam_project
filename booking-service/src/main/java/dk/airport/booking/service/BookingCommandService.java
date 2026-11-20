package dk.airport.booking.service;

import dk.airport.booking.domain.*;
import dk.airport.booking.graphql.input.PassengerInput;
import dk.airport.booking.messaging.BookingEvents;
import dk.airport.booking.messaging.EventPublisher;
import dk.airport.booking.query.BookingOverviewProjector;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.repository.PassengerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class BookingCommandService {

    private static final Logger log = LoggerFactory.getLogger(BookingCommandService.class);
    private static final int REFERENCE_ATTEMPTS = 5;

    private final BookingRepository bookings;
    private final PassengerRepository passengers;
    private final FlightClient flightClient;
    private final EventPublisher events;
    private final BookingOverviewProjector projector;
    private final BookingReferenceGenerator references = new BookingReferenceGenerator();

    public BookingCommandService(BookingRepository bookings, PassengerRepository passengers,
                          FlightClient flightClient, EventPublisher events, BookingOverviewProjector projector) {
        this.bookings = bookings;
        this.passengers = passengers;
        this.flightClient = flightClient;
        this.events = events;
        this.projector = projector;
    }

    /** The booking or NOT_FOUND. Public because BookingController checks ownership before cancel/check-in. */
    public Booking requireBooking(String reference) {
        return bookings.findByBookingReference(normalizeReference(reference))
                .orElseThrow(() -> ApiException.notFound("Booking", reference));
    }

    // ------------------------------------------------------------ mutations

    @Transactional
    public Booking createBooking(Long flightId, String seatNumber, PassengerInput input) {
        String seat = seatNumber.trim().toUpperCase();
        if (bookings.existsActiveSeat(flightId, seat)) {
            throw new ApiException(ErrorCode.SEAT_TAKEN, "Seat " + seat + " is already taken on flight " + flightId);
        }

        FlightSeatInfo info = flightClient.fetchFlightSeat(flightId, seat);

        Passenger passenger = passengers.findByEmailIgnoreCase(input.email().trim())
                .map(p -> {
                    p.update(input.firstName().trim(), input.lastName().trim(),
                            input.passportNumber().trim().toUpperCase(), input.dateOfBirth());
                    return p;
                })
                .orElseGet(() -> passengers.save(new Passenger(input.firstName().trim(), input.lastName().trim(),
                        input.email().trim().toLowerCase(), input.passportNumber().trim().toUpperCase(),
                        input.dateOfBirth())));

        Booking booking = null;
        for (int attempt = 1; attempt <= REFERENCE_ATTEMPTS && booking == null; attempt++) {
            String reference = references.generate();
            if (bookings.existsByBookingReference(reference)) {
                continue;
            }
            try {
                booking = bookings.saveAndFlush(new Booking(reference, passenger, info.flightId(), info.flightNumber(),
                        info.scheduledDeparture(), info.gate(), info.flightStatus(), info.seatNumber(),
                        info.price(), info.currency()));
            } catch (DataIntegrityViolationException e) {
                String msg = String.valueOf(e.getMostSpecificCause().getMessage());
                if (msg.contains("ux_booking_active_seat")) {
                    throw new ApiException(ErrorCode.SEAT_TAKEN,
                            "Seat " + seat + " is already taken on flight " + flightId);
                }
                if (msg.contains("booking_reference")) {
                    log.warn("Booking reference collision on {}, retrying (attempt {})", reference, attempt);
                    continue;
                }
                throw e;
            }
        }
        if (booking == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Could not generate a unique booking reference, please retry");
        }

        projector.bookingCreated(booking.getId(), passenger.getId());
        events.publish(BookingEvents.CREATED, BookingEvents.payload(booking, null));
        log.info("Created booking {} for flight {} seat {} ({} {})", booking.getBookingReference(),
                booking.getFlightNumber(), booking.getSeatNumber(), booking.getPrice(), booking.getCurrency());
        return booking;
    }

    @Transactional
    public Booking cancelBooking(String reference) {
        Booking booking = requireBooking(reference);
        booking.cancel();
        projectBooking(booking);
        events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, "Cancelled by passenger"));
        log.info("Booking {} cancelled by passenger", booking.getBookingReference());
        return booking;
    }

    @Transactional
    public Booking checkIn(String reference) {
        Booking booking = requireBooking(reference);
        booking.checkIn();
        projectBooking(booking);
        events.publish(BookingEvents.CHECKED_IN, BookingEvents.payload(booking, null));
        log.info("Booking {} checked in", booking.getBookingReference());
        return booking;
    }

    // ------------------------------------------------------------ event driven transitions

    /** payment.completed */
    @Transactional
    public void onPaymentCompleted(String reference) {
        Optional<Booking> found = bookings.findByBookingReferenceForUpdate(normalizeReference(reference));
        if (found.isEmpty()) {
            log.warn("payment.completed for unknown booking {}", reference);
            return;
        }
        Booking booking = found.get();
        switch (booking.getStatus()) {
            case PENDING_PAYMENT -> {
                booking.confirm();
                projectBooking(booking);
                events.publish(BookingEvents.CONFIRMED, BookingEvents.payload(booking, null));
                log.info("Booking {} confirmed after payment", booking.getBookingReference());
            }
            case CONFIRMED, CHECKED_IN -> log.info("Booking {} already {} - ignoring payment.completed",
                    booking.getBookingReference(), booking.getStatus());
            // Ticket 11, compensation for a late payment: the booking was cancelled (payment timeout, passenger or
            // flight) before the money arrived. The booking stays CANCELLED - its seat may already be sold again -
            // and booking.cancelled is published once more, so payment-service refunds the payment (it refunds every
            // COMPLETED payment of a cancelled booking). flight-service ignores it if someone else holds the seat.
            case CANCELLED -> {
                events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, LATE_PAYMENT_REASON));
                log.warn("payment.completed for CANCELLED booking {} - published booking.cancelled again "
                        + "so the payment is refunded", booking.getBookingReference());
            }
            default -> log.warn("payment.completed for booking {} in unhandled status {} - ignoring",
                    booking.getBookingReference(), booking.getStatus());
        }
    }

    public static final String LATE_PAYMENT_REASON = "Late payment for cancelled booking - payment refunded";
    public static final String PAYMENT_TIMEOUT_REASON = "Payment timeout";

    /**
     * Ticket 11, compensation for a missing payment: PENDING_PAYMENT bookings older than {@code timeout} are cancelled
     * and booking.cancelled frees the seat in flight-service. Called by {@link PaymentTimeoutJob}.
     *
     * @return number of bookings cancelled
     */
    @Transactional
    public int cancelExpiredPayments(Duration timeout, int batchSize) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(timeout);
        List<Booking> expired = bookings.lockExpiredPendingPayments(cutoff, batchSize);
        for (Booking booking : expired) {
            booking.cancel();
            projectBooking(booking);
            events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, PAYMENT_TIMEOUT_REASON));
            log.info("Booking {} cancelled: no payment within {} (created {})",
                    booking.getBookingReference(), timeout, booking.getCreatedAt());
        }
        return expired.size();
    }

    /** payment.failed */
    @Transactional
    public void onPaymentFailed(String reference, String failureReason) {
        Optional<Booking> found = bookings.findByBookingReferenceForUpdate(normalizeReference(reference));
        if (found.isEmpty()) {
            log.warn("payment.failed for unknown booking {}", reference);
            return;
        }
        Booking booking = found.get();
        String reason = "Payment failed"
                + (failureReason == null || failureReason.isBlank() ? "" : ": " + failureReason);
        switch (booking.getStatus()) {
            case PENDING_PAYMENT -> {
                booking.cancel();
                projectBooking(booking);
                events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, reason));
                log.info("Booking {} cancelled: {}", booking.getBookingReference(), reason);
            }
            // Ticket 10: payment-service refuses new payments once one is COMPLETED, so a payment.failed can only
            // happen before the payment.completed. Seeing it on a CONFIRMED booking means the two events were
            // delivered out of order. CANCELLED is terminal and wins in any order: cancel, and the booking.cancelled
            // event makes payment-service refund the completed payment - the same end state as in-order delivery.
            case CONFIRMED, CHECKED_IN -> {
                BookingStatus before = booking.getStatus();
                booking.cancel();
                projectBooking(booking);
                events.publish(BookingEvents.CANCELLED,
                        BookingEvents.payload(booking, reason + " (delivered after payment.completed)"));
                log.warn("payment.failed for {} booking {} arrived out of order - cancelling, "
                        + "the completed payment is refunded", before, booking.getBookingReference());
            }
            case CANCELLED -> log.info("payment.failed for already CANCELLED booking {} - ignoring",
                    booking.getBookingReference());
            default -> log.info("payment.failed for booking {} in status {} - ignoring",
                    booking.getBookingReference(), booking.getStatus());
        }
    }

    /** flight.cancelled */
    @Transactional
    public int onFlightCancelled(Long flightId, OffsetDateTime occurredAt) {
        List<Booking> affected = bookings.findByFlightIdAndStatusNot(flightId, BookingStatus.CANCELLED);
        for (Booking booking : affected) {
            booking.cancel();
            booking.updateFlightSnapshot("CANCELLED", null, occurredAt);
            events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, "Flight cancelled"));
        }
        // bookings already cancelled still get the snapshot updated
        bookings.findByFlightId(flightId).forEach(b -> b.updateFlightSnapshot("CANCELLED", null, occurredAt));
        bookings.flush();
        projector.flightBookingsChanged(flightId);
        log.info("Flight {} cancelled -> {} booking(s) cancelled", flightId, affected.size());
        return affected.size();
    }

    /** flight.status.changed / flight.gate.changed. Events older than the snapshot's fields are ignored (ticket 10). */
    @Transactional
    public void onFlightSnapshotChanged(Long flightId, String flightStatus, String gate, OffsetDateTime occurredAt) {
        List<Booking> affected = bookings.findByFlightId(flightId);
        affected.forEach(b -> b.updateFlightSnapshot(flightStatus, gate, occurredAt));
        bookings.flush();
        projector.flightBookingsChanged(flightId);
        log.info("Flight {} event (occurredAt {}) applied to {} booking(s): status={}, gate={}",
                flightId, occurredAt, affected.size(), flightStatus, gate);
    }

    private void projectBooking(Booking booking) {
        bookings.flush();
        projector.bookingChanged(booking.getId());
    }

    private static String normalizeReference(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
    }
}
