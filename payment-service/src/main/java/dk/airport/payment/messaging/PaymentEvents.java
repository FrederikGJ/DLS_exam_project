package dk.airport.payment.messaging;

import dk.airport.payment.domain.Payment;

import java.math.BigDecimal;

/** Event names and payloads published by payment-service. Documented in docs/events.md. */
public final class PaymentEvents {

    public static final String COMPLETED = "payment.completed";
    public static final String FAILED = "payment.failed";
    public static final String REFUNDED = "payment.refunded";

    private PaymentEvents() {}

    public record Completed(Long paymentId, String bookingReference, BigDecimal amount, String currency, String cardLast4) {}

    public record Failed(Long paymentId, String bookingReference, BigDecimal amount, String currency, String cardLast4,
                         String failureReason) {}

    public record Refunded(Long paymentId, String bookingReference, BigDecimal amount, String currency) {}

    public static Completed completed(Payment p) {
        return new Completed(p.getId(), p.getBookingReference(), p.getAmount(), p.getCurrency(), p.getCardLast4());
    }

    public static Failed failed(Payment p) {
        return new Failed(p.getId(), p.getBookingReference(), p.getAmount(), p.getCurrency(), p.getCardLast4(), p.getFailureReason());
    }

    public static Refunded refunded(Payment p) {
        return new Refunded(p.getId(), p.getBookingReference(), p.getAmount(), p.getCurrency());
    }
}
