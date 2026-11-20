-- Ticket 10: commutative booking.* handler.
-- occurredAt of the newest booking event applied to the snapshot's status. Older events are ignored and
-- CANCELLED is terminal, so the same events give the same snapshot whatever order they arrive in.
-- Version 10 leaves V3-V9 free for migrations on the other ticket branches.
ALTER TABLE booking_snapshot ADD COLUMN event_occurred_at TIMESTAMPTZ;
