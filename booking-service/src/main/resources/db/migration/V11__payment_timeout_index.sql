-- Ticket 11: PaymentTimeoutJob runs every 30 s in every pod and looks for unpaid bookings older than the timeout.
-- A partial index only holds PENDING_PAYMENT rows (few at any time), so the lookup stays cheap however many
-- confirmed/cancelled bookings the table grows to.
CREATE INDEX idx_booking_pending_payment_created ON booking (created_at) WHERE status = 'PENDING_PAYMENT';
