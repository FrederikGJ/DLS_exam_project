-- Saga compensations (dev plan DP-32, see docs/architecture.md "Saga").
-- Why a booking was cancelled: by the passenger, failed payment, cancelled flight, or no payment within the payment
-- timeout. Kept so a late payment's compensation (booking.payment.rejected) and "Min booking" can say why.
ALTER TABLE booking ADD COLUMN cancellation_reason VARCHAR(200);
ALTER TABLE booking_overview ADD COLUMN cancellation_reason VARCHAR(200);

-- PaymentTimeoutJob asks every 30 s for the oldest unpaid bookings; a partial index keeps that cheap however many
-- confirmed and cancelled bookings there are.
CREATE INDEX idx_booking_pending_payment ON booking (created_at) WHERE status = 'PENDING_PAYMENT';
