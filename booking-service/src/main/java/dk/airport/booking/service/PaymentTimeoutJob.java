package dk.airport.booking.service;

import dk.airport.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Saga compensation for a payment that never comes (dev plan DP-32). A booking holds its seat from createBooking on
 * - booking-service's unique index refuses the seat to anyone else - but flight-service only marks the seat taken
 * when the booking is confirmed. Without a timeout an abandoned booking would block the seat forever. Every
 * {@code app.booking.payment-timeout-check-interval-ms} the job cancels bookings that have been PENDING_PAYMENT for
 * longer than {@code app.booking.payment-timeout} (default 15 minutes) and publishes booking.cancelled like any other
 * cancellation: the seat is free again, baggage-service's snapshot is cancelled, and should a payment still arrive,
 * booking-service answers it with booking.payment.rejected (see BookingService#onPaymentCompleted).
 *
 * <p>Safe with several pods: each booking is cancelled in its own transaction and re-checked there, and the
 * {@code @Version} on Booking lets only one of two concurrent writers commit (another pod's job, or
 * payment.completed).
 */
@Component
public class PaymentTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutJob.class);
    private static final int BATCH_SIZE = 100;

    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final Duration timeout;

    public PaymentTimeoutJob(BookingRepository bookings, BookingService bookingService,
                             @Value("${app.booking.payment-timeout}") Duration timeout) {
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.timeout = timeout;
    }

    @Scheduled(initialDelayString = "${app.booking.payment-timeout-check-interval-ms}",
            fixedDelayString = "${app.booking.payment-timeout-check-interval-ms}")
    public void cancelUnpaidBookings() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(timeout);
        List<Long> expired = bookings.findUnpaidCreatedBefore(cutoff, Limit.of(BATCH_SIZE));
        int cancelled = 0;
        for (Long id : expired) {
            try {
                if (bookingService.expireUnpaidBooking(id, cutoff, reason())) {
                    cancelled++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("Booking {} changed while its payment timeout was handled (paid at the same moment?) - "
                        + "checked again on the next run", id);
            }
        }
        if (cancelled > 0) {
            log.info("Payment timeout: cancelled {} booking(s) unpaid for more than {}", cancelled, timeout);
        }
    }

    /** "Payment not received within 15 minutes" - also shown to the passenger under "Min booking". */
    String reason() {
        long seconds = timeout.toSeconds();
        String amount = seconds % 60 == 0 ? plural(seconds / 60, "minute") : plural(seconds, "second");
        return "Payment not received within " + amount;
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
