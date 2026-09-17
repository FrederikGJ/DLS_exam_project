package dk.airport.booking.query;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.Passenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Keeps the read model {@code booking_overview} up to date (CQRS, dev plan DP-28). Two kinds of input:
 * <ul>
 *   <li><b>booking-service's own changes</b> - {@link #onBookingChanged} is called by BookingService inside the
 *       command's transaction, so the overview commits (or rolls back) together with the booking, and a passenger
 *       who checks in sees CHECKED_IN on the very next read;</li>
 *   <li><b>other services' events</b> - {@link #onPayment} and {@link #onBaggage} are called by
 *       IncomingEventHandler for payment.* and baggage.* events, in the handler's transaction together with the
 *       processed_event row (a duplicate event is skipped before it gets here).</li>
 * </ul>
 * Guards: an event for a booking this service does not know is logged and ignored (payment-service accepts any
 * reference), and every payment/bag line is merged with {@link OverviewPayment#merge}/{@link OverviewBaggage#merge},
 * so an older event that arrives late never overwrites a newer status. Each method locks the booking's row first;
 * see BookingOverviewRepository.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class BookingOverviewProjector {

    private static final Logger log = LoggerFactory.getLogger(BookingOverviewProjector.class);

    private final BookingOverviewRepository overviews;

    public BookingOverviewProjector(BookingOverviewRepository overviews) {
        this.overviews = overviews;
    }

    /** booking created / confirmed / cancelled / checked in, flight snapshot changed. */
    public void onBookingChanged(Booking booking) {
        BookingOverview overview = overviews.lockByBookingId(booking.getId())
                .orElseGet(() -> new BookingOverview(booking.getId()));
        overview.copyFrom(booking);
        overviews.save(overview);
    }

    /** A returning passenger's details changed on a new booking: the copy on their earlier bookings follows. */
    public void onPassengerChanged(Passenger passenger) {
        overviews.lockByPassengerId(passenger.getId()).forEach(o -> o.copyFrom(passenger));
    }

    /** payment.completed / payment.failed / payment.refunded. */
    public void onPayment(String bookingReference, OverviewPayment observed) {
        overviews.lockByBookingReference(normalize(bookingReference)).ifPresentOrElse(
                o -> o.mergePayment(observed),
                () -> log.warn("Payment {} is for unknown booking {} - not in the booking overview",
                        observed.paymentId(), bookingReference));
    }

    /** baggage.registered / baggage.status.changed. */
    public void onBaggage(String bookingReference, OverviewBaggage observed) {
        overviews.lockByBookingReference(normalize(bookingReference)).ifPresentOrElse(
                o -> o.mergeBaggage(observed),
                () -> log.warn("Baggage {} is for unknown booking {} - not in the booking overview",
                        observed.tagNumber(), bookingReference));
    }

    private static String normalize(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
    }
}
