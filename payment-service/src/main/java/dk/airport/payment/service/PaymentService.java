package dk.airport.payment.service;

import dk.airport.payment.domain.ApiException;
import dk.airport.payment.domain.ErrorCode;
import dk.airport.payment.domain.Payment;
import dk.airport.payment.domain.PaymentStatus;
import dk.airport.payment.graphql.input.PayInput;
import dk.airport.payment.messaging.EventPublisher;
import dk.airport.payment.messaging.PaymentEvents;
import dk.airport.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentSimulator simulator;
    private final EventPublisher events;

    public PaymentService(PaymentRepository payments, PaymentSimulator simulator, EventPublisher events) {
        this.payments = payments;
        this.simulator = simulator;
        this.events = events;
    }

    public Optional<Payment> payment(Long id) {
        return payments.findById(id);
    }

    public List<Payment> paymentsByBooking(String reference) {
        return payments.findByBookingReferenceOrderByCreatedAt(reference.trim().toUpperCase());
    }

    /**
     * Runs the simulated gateway and records the outcome. A declined card results in a FAILED payment
     * row (returned to the caller, not an error) and a payment.failed event.
     */
    @Transactional
    public Payment pay(PayInput in) {
        String reference = in.bookingReference().trim().toUpperCase();
        if (payments.existsByBookingReferenceAndStatus(reference, PaymentStatus.COMPLETED)) {
            throw new ApiException(ErrorCode.ALREADY_PAID, "Booking " + reference + " is already paid");
        }
        String last4 = PaymentSimulator.cardLast4(in.cardNumber());
        PaymentSimulator.Result result = simulator.evaluate(in.cardNumber(), in.expiry());

        Payment payment = payments.save(new Payment(reference, in.amount(), last4,
                result.approved() ? PaymentStatus.COMPLETED : PaymentStatus.FAILED,
                result.failureReason()));

        if (result.approved()) {
            events.publish(PaymentEvents.COMPLETED, PaymentEvents.completed(payment));
            log.info("Payment {} for booking {} COMPLETED (card ****{})", payment.getId(), reference, last4);
        } else {
            events.publish(PaymentEvents.FAILED, PaymentEvents.failed(payment));
            log.info("Payment {} for booking {} FAILED: {}", payment.getId(), reference, result.failureReason());
        }
        return payment;
    }

    @Transactional
    public Payment refund(Long paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment", paymentId));
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "Only COMPLETED payments can be refunded (payment " + paymentId + " is " + payment.getStatus() + ")");
        }
        doRefund(payment);
        return payment;
    }

    /** Called from booking.cancelled events: refund every COMPLETED payment of the booking. */
    @Transactional
    public int refundBooking(String bookingReference) {
        List<Payment> completed = payments.findByBookingReferenceAndStatus(bookingReference, PaymentStatus.COMPLETED);
        completed.forEach(this::doRefund);
        return completed.size();
    }

    private void doRefund(Payment payment) {
        payment.setStatus(PaymentStatus.REFUNDED);
        events.publish(PaymentEvents.REFUNDED, PaymentEvents.refunded(payment));
        log.info("Payment {} for booking {} REFUNDED", payment.getId(), payment.getBookingReference());
    }
}
