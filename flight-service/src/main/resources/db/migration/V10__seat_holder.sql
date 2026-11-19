-- Ticket 10: commutative booking.confirmed / booking.cancelled handler for seats.
--  held_by_booking        booking reference of the last booking event applied to the seat
--  availability_event_at  occurredAt of that event
-- With these a booking.cancelled only frees the seat if it comes from the booking that holds it, a cancelled
-- booking can never take the seat again, and older events are ignored - so the order of delivery does not matter.
-- Version 10 leaves V4-V9 free for migrations on the other ticket branches.
ALTER TABLE seat ADD COLUMN held_by_booking       VARCHAR(6);
ALTER TABLE seat ADD COLUMN availability_event_at TIMESTAMPTZ;
