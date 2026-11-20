package dk.airport.booking.query;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.Passenger;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.repository.OffsetLimit;
import dk.airport.booking.repository.PassengerRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class BookingQueryService {

    private final BookingRepository bookings;
    private final PassengerRepository passengers;
    private final BookingOverviewRepository overviews;

    public BookingQueryService(BookingRepository bookings, PassengerRepository passengers,
                               BookingOverviewRepository overviews) {
        this.bookings = bookings;
        this.passengers = passengers;
        this.overviews = overviews;
    }

    public Optional<BookingOverview> bookingOverview(String reference) {
        return overviews.find(reference.trim().toUpperCase(Locale.ROOT));
    }

    public Optional<Booking> booking(Long id) {
        return bookings.findById(id);
    }

    public Optional<Booking> bookingByReference(String reference) {
        return bookings.findByBookingReference(reference.trim().toUpperCase(Locale.ROOT));
    }

    public List<Booking> bookingsByPassenger(String email) {
        return bookings.findByPassengerEmail(email.trim());
    }

    /** One page of the passenger's bookings, newest first. {@code null} offset/limit = defaults (0 / 50). */
    public List<Booking> bookingsByPassenger(String email, Integer offset, Integer limit) {
        return bookings.findByPassengerEmail(email.trim(), OffsetLimit.of(offset, limit, Sort.unsorted()));
    }

    public List<Booking> bookingsByPassengerId(Long passengerId) {
        return bookings.findByPassengerIdOrderByCreatedAtDesc(passengerId);
    }

    public Optional<Passenger> passenger(Long id) {
        return passengers.findById(id);
    }
}
