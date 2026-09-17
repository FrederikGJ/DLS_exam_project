package dk.airport.booking.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One payment of a booking as the read model knows it, built from payment.completed, payment.failed and
 * payment.refunded (payment-service owns the payment). Stored in the JSON column {@code booking_overview.payments}.
 *
 * <p>{@code updatedAt} is the {@code occurredAt} of the newest event applied to the line and decides which event's
 * status wins; {@code createdAt} is the earliest {@code occurredAt} seen. The fields a later event does not carry
 * (payment.refunded has no card digits) are kept from the earlier one.
 */
public record OverviewPayment(Long paymentId, String status, BigDecimal amount, String currency, String cardLast4,
                              String failureReason, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    /**
     * Combines two observations of the same payment. The result does not depend on the order the events arrived in
     * ({@code a.merge(b)} equals {@code b.merge(a)}) and applying the same event twice changes nothing, so a
     * redelivered or overtaken event can never move the line back to an older status.
     */
    public OverviewPayment merge(OverviewPayment other) {
        OverviewPayment newer = Lines.newer(this, other, OverviewPayment::updatedAt, OverviewPayment::status);
        OverviewPayment older = newer == this ? other : this;
        return new OverviewPayment(paymentId,
                newer.status,
                Lines.firstNonNull(newer.amount, older.amount),
                Lines.firstNonNull(newer.currency, older.currency),
                Lines.firstNonNull(newer.cardLast4, older.cardLast4),
                Lines.firstNonNull(newer.failureReason, older.failureReason),
                Lines.earliest(createdAt, other.createdAt),
                newer.updatedAt);
    }
}
