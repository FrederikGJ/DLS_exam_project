package dk.airport.booking.service;

import dk.airport.booking.domain.*;
import dk.airport.booking.graphql.input.PassengerInput;
import dk.airport.booking.messaging.BookingEvents;
import dk.airport.booking.messaging.EventPublisher;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.repository.PassengerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final int REFERENCE_ATTEMPTS = 5;

    private final BookingRepository bookings;
    private final PassengerRepository passengers;
    private final FlightClient flightClient;
    private final EventPublisher events;
    private final BookingReferenceGenerator references = new BookingReferenceGenerator();

    public BookingService(BookingRepository bookings, PassengerRepository passengers,
                          FlightClient flightClient, EventPublisher events) {
        this.bookings = bookings;
        this.passengers = passengers;
        this.flightClient = flightClient;
        this.events = events;
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
                    return p;
                })
                .orElseGet(() -> passengers.save(new Passenger(input.firstName().trim(), input.lastName().trim(),
                        input.email().trim().toLowerCase(), input.passportNumber().trim().toUpperCase(), input.dateOfBirth())));

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
                    throw new ApiException(ErrorCode.SEAT_TAKEN, "Seat " + seat + " is already taken on flight " + flightId);
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
        log.info("Created booking {} for flight {} seat {} ({} {})", booking.getBookingReference(),
                booking.getFlightNumber(), booking.getSeatNumber(), booking.getPrice(), booking.getCurrency());
        return booking;
    }

    @Transactional
    public Booking cancelBooking(String reference) {
        Booking booking = requireBooking(reference);
        booking.cancel();
        events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, "Cancelled by passenger"));
        log.info("Booking {} cancelled by passenger", booking.getBookingReference());
        return booking;
    }

    @Transactional
    public Booking checkIn(String reference) {
        Booking booking = requireBooking(reference);
        booking.checkIn();
        events.publish(BookingEvents.CHECKED_IN, BookingEvents.payload(booking, null));
        log.info("Booking {} checked in", booking.getBookingReference());
        return booking;
    }

    // ------------------------------------------------------------ event driven transitions

    /** payment.completed */
    @Transactional
    public void onPaymentCompleted(String reference) {
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
                log.info("Booking {} confirmed after payment", booking.getBookingReference());
            }
            case CONFIRMED, CHECKED_IN -> log.info("Booking {} already {} - ignoring payment.completed",
                    booking.getBookingReference(), booking.getStatus());
            case CANCELLED -> log.warn("payment.completed received for CANCELLED booking {} - no state change",
                    booking.getBookingReference());
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
            booking.cancel();
            String reason = "Payment failed" + (failureReason == null || failureReason.isBlank() ? "" : ": " + failureReason);
            events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, reason));
            log.info("Booking {} cancelled: {}", booking.getBookingReference(), reason);
        } else {
            log.info("payment.failed for booking {} in status {} - ignoring", booking.getBookingReference(), booking.getStatus());
        }
    }

    /** flight.cancelled */
    @Transactional
    public int onFlightCancelled(Long flightId) {
        List<Booking> affected = bookings.findByFlightIdAndStatusNot(flightId, BookingStatus.CANCELLED);
        for (Booking booking : affected) {
            booking.cancel();
            booking.updateFlightSnapshot("CANCELLED", null);
            events.publish(BookingEvents.CANCELLED, BookingEvents.payload(booking, "Flight cancelled"));
        }
        // bookings already cancelled still get the snapshot updated
        bookings.findByFlightId(flightId).forEach(b -> b.updateFlightSnapshot("CANCELLED", null));
        log.info("Flight {} cancelled -> {} booking(s) cancelled", flightId, affected.size());
        return affected.size();
    }

    /** flight.status.changed / flight.gate.changed */
    @Transactional
    public void onFlightSnapshotChanged(Long flightId, String flightStatus, String gate) {
        List<Booking> affected = bookings.findByFlightId(flightId);
        affected.forEach(b -> b.updateFlightSnapshot(flightStatus, gate));
        log.info("Flight {} snapshot updated on {} booking(s): status={}, gate={}", flightId, affected.size(), flightStatus, gate);
    }

    private static String normalizeReference(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase();
    }
}
