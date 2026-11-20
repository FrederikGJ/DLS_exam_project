package dk.airport.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Ticket 11 (saga Flow A, compensation 1): a booking that is not paid within {@code app.saga.payment-timeout}
 * is cancelled, so its seat is not blocked forever. Runs in every pod; the row locks with SKIP LOCKED in
 * {@code BookingRepository.lockExpiredPendingPayments} make sure each booking is cancelled exactly once.
 */
@Component
public class PaymentTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutJob.class);
    private static final int BATCH_SIZE = 100;

    private final BookingCommandService bookingService;
    private final Duration timeout;

    public PaymentTimeoutJob(BookingCommandService bookingService,
                             @Value("${app.saga.payment-timeout}") Duration timeout) {
        this.bookingService = bookingService;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${app.saga.payment-timeout-check-interval-ms}",
               initialDelayString = "${app.saga.payment-timeout-check-interval-ms}")
    public void cancelExpiredPayments() {
        int cancelled;
        do {
            cancelled = bookingService.cancelExpiredPayments(timeout, BATCH_SIZE);
            if (cancelled > 0) {
                log.info("Payment timeout: cancelled {} booking(s) not paid within {}", cancelled, timeout);
            }
        } while (cancelled == BATCH_SIZE);
    }
}
