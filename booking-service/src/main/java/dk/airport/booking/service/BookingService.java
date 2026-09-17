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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Command side of booking-service: every change to a booking or passenger happens here, publishes its event through
 * the outbox and updates the read model {@code booking_overview} through {@link BookingOverviewProjector} in the same
 * transaction (CQRS, dev plan DP-28). The queries below read the write model and serve the booking flow itself
 * (e.g. polling for CONFIRMED after payment); "Min booking" reads the overview via BookingQueryService.
 */
@Service
@Transactional(readOnly = true)
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final int REFERENCE_ATTEMPTS = 5;

    private final BookingRepository bookings;
    private final PassengerRepository passengers;
    private final FlightClient flightClient;
    private final EventPublisher events;
    private final BookingOverviewProjector overview;
    private final BookingReferenceGenerator references = new BookingReferenceGenerator();

    public BookingService(BookingRepository bookings, PassengerRepository passengers,
                          FlightClient flightClient, EventPublisher events, BookingOverviewProjector overview) {
        this.bookings = bookings;
        this.passengers = passengers;
        this.flightClient = flightClient;
        this.events = events;
        this.overview = overview;
    }

    // ------------------------------------------------------------ queries

    public Optional<Booking> booking(Long id) {
        return bookings.findById(id);
    }

    public Optional<Booking> bookingByReference(String reference) {
        return bookings.findByBookingReference(normalizeReference(reference));
    }

    public Booking requireBooking(String reference) {
        return bookingByReference(reference).orElseThrow(() -> ApiException.notFound("Booking", reference));
    }

    public List<Booking> bookingsByPassenger(String email) {
        return bookings.findByPassengerEmail(email.trim());
    }

    public List<Booking> bookingsByPassengerId(Long passengerId) {
        return bookings.findByPassengerIdOrderByCreatedAtDesc(passengerId);
    }

    public Optional<Passenger> passenger(Long id) {
        return passengers.findById(id);
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
                    overview.onPassengerChanged(p);
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

        events.publish(BookingEvents.CREATED, BookingEvents.payload(booking, null));
        overview.onBookingChanged(booking);
        log.info("Created booking {} for flight {} seat {} ({} {})", booking.getBookingReference(),
                booking.getFlightNumber(), booking.getSeatNumber(), booking.getPrice(), booking.getCurrency());
        return booking;
    }

    @Transactional
    public Booking cancelBooking(String reference) {
        Booking booking = requireBooking(reference);
        booking.cancel("Cancelled by passenger");
        events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, booking.getCancellationReason()));
        overview.onBookingChanged(booking);
        log.info("Booking {} cancelled by passenger", booking.getBookingReference());
        return booking;
    }

    @Transactional
    public Booking checkIn(String reference) {
        Booking booking = requireBooking(reference);
        booking.checkIn();
        events.publish(BookingEvents.CHECKED_IN, BookingEvents.payload(booking, null));
        overview.onBookingChanged(booking);
        log.info("Booking {} checked in", booking.getBookingReference());
        return booking;
    }

    // ------------------------------------------------------------ event driven transitions

    /**
     * payment.completed. Normally confirms the booking. If the booking was cancelled before the payment arrived (the
     * payment timeout, the passenger or a cancelled flight got there first), the money must go back: the booking stays
     * CANCELLED and booking.payment.rejected asks payment-service to refund this payment (saga compensation, DP-32).
     */
    @Transactional
    public void onPaymentCompleted(String reference, Long paymentId, BigDecimal amount, String currency) {
        Optional<Booking> found = bookings.findByBookingReference(normalizeReference(reference));
        if (found.isEmpty()) {
            log.warn("payment.completed for unknown booking {}", reference);
            return;
        }
        Booking booking = found.get();
        switch (booking.getStatus()) {
            case PENDING_PAYMENT -> {
                booking.confirm();
                events.publish(BookingEvents.CONFIRMED, BookingEvents.payload(booking, null));
                overview.onBookingChanged(booking);
                log.info("Booking {} confirmed after payment", booking.getBookingReference());
            }
            case CONFIRMED, CHECKED_IN -> log.info("Booking {} already {} - ignoring payment.completed",
                    booking.getBookingReference(), booking.getStatus());
            case CANCELLED -> {
                events.publish(BookingEvents.PAYMENT_REJECTED,
                        BookingEvents.paymentRejected(booking, paymentId, amount, currency));
                log.warn("Payment {} arrived for CANCELLED booking {} ({}) - published {} so it is refunded",
                        paymentId, booking.getBookingReference(), booking.getCancellationReason(),
                        BookingEvents.PAYMENT_REJECTED);
            }
            default -> log.warn("payment.completed for booking {} in unhandled status {} - ignoring",
                    booking.getBookingReference(), booking.getStatus());
        }
    }

    /** payment.failed */
    @Transactional
    public void onPaymentFailed(String reference, String failureReason) {
        Optional<Booking> found = bookings.findByBookingReference(normalizeReference(reference));
        if (found.isEmpty()) {
            log.warn("payment.failed for unknown booking {}", reference);
            return;
        }
        Booking booking = found.get();
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            String reason = "Payment failed"
                    + (failureReason == null || failureReason.isBlank() ? "" : ": " + failureReason);
            booking.cancel(reason);
            events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, reason));
            overview.onBookingChanged(booking);
            log.info("Booking {} cancelled: {}", booking.getBookingReference(), reason);
        } else {
            log.info("payment.failed for booking {} in status {} - ignoring",
                    booking.getBookingReference(), booking.getStatus());
        }
    }

    /**
     * Payment timeout (saga compensation, DP-32): cancels a booking that is still unpaid and was created before
     * {@code cutoff}, which frees its seat for others. Called by PaymentTimeoutJob, one booking per transaction.
     * Checked again inside the transaction, and {@code @Version} on Booking stops a payment.completed that commits at
     * the same moment from being overwritten; the loser is retried and then sees the other's result.
     *
     * @return true if the booking was cancelled now
     */
    @Transactional
    public boolean expireUnpaidBooking(Long bookingId, OffsetDateTime cutoff, String reason) {
        Optional<Booking> found = bookings.findById(bookingId);
        if (found.isEmpty() || found.get().getStatus() != BookingStatus.PENDING_PAYMENT
                || !found.get().getCreatedAt().isBefore(cutoff)) {
            return false;
        }
        Booking booking = found.get();
        booking.cancel(reason);
        events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, reason));
        overview.onBookingChanged(booking);
        log.info("Booking {} cancelled: {}", booking.getBookingReference(), reason);
        return true;
    }

    /** flight.cancelled */
    @Transactional
    public int onFlightCancelled(Long flightId, OffsetDateTime occurredAt) {
        int cancelled = 0;
        // one pass over all bookings on the flight: bookings already cancelled still get the snapshot updated
        for (Booking booking : bookings.findByFlightIdOrderById(flightId)) {
            booking.applyFlightSnapshot("CANCELLED", null, occurredAt);
            if (!booking.isCancelled()) {
                booking.cancel("Flight cancelled");
                events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, "Flight cancelled"));
                cancelled++;
            }
            overview.onBookingChanged(booking);
        }
        log.info("Flight {} cancelled -> {} booking(s) cancelled", flightId, cancelled);
        return cancelled;
    }

    /**
     * flight.status.changed / flight.gate.changed. An event older than what a booking already knows changes nothing
     * (see {@link Booking#applyFlightSnapshot}), so flight events may arrive in any order.
     */
    @Transactional
    public void onFlightSnapshotChanged(Long flightId, String flightStatus, String gate, OffsetDateTime occurredAt) {
        List<Booking> affected = bookings.findByFlightIdOrderById(flightId);
        int changed = 0;
        for (Booking b : affected) {
            if (b.applyFlightSnapshot(flightStatus, gate, occurredAt)) {
                changed++;
                overview.onBookingChanged(b);
            }
        }
        log.info("Flight {} event (status={}, gate={}, occurredAt {}) changed {} of {} booking(s)",
                flightId, flightStatus, gate, occurredAt, changed, affected.size());
    }

    private static String normalizeReference(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase();
    }
}
