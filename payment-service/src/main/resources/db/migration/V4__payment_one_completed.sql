-- At most one COMPLETED payment per booking reference (dev plan DP-30).
-- PaymentService.pay checks for a COMPLETED payment before it charges (ALREADY_PAID), but a check followed by an insert
-- lets two simultaneous pay calls for the same booking both pass the check and both complete - the card would be
-- charged twice and booking-service ignores the second payment.completed, so nothing refunds it. This partial unique
-- index makes the second insert fail inside its transaction (mapped to ALREADY_PAID). A refunded payment leaves the
-- index, so a booking can be paid again after a refund.
CREATE UNIQUE INDEX ux_payment_one_completed ON payment (booking_reference) WHERE status = 'COMPLETED';
