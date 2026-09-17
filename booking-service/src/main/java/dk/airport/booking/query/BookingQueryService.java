package dk.airport.booking.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Query side of booking-service (CQRS, dev plan DP-28): answers from the read model {@code booking_overview} only,
 * one indexed lookup per request and never a join or a call to another service. The command side is
 * BookingService; the only link between the two is {@link BookingOverviewProjector}.
 */
@Service
@Transactional(readOnly = true)
public class BookingQueryService {

    private final BookingOverviewRepository overviews;

    public BookingQueryService(BookingOverviewRepository overviews) {
        this.overviews = overviews;
    }

    public Optional<BookingOverview> overview(String reference) {
        return overviews.findByBookingReference(reference.trim().toUpperCase(Locale.ROOT));
    }

    /** All bookings made with this e-mail address, newest first. */
    public List<BookingOverview> overviewsForPassenger(String email) {
        return overviews.findByPassengerEmailOrderByCreatedAtDesc(email.trim().toLowerCase(Locale.ROOT));
    }
}
