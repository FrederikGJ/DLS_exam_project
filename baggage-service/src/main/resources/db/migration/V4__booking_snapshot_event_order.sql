-- Commutative booking snapshot (dev plan DP-31, see docs/events.md "Rækkefølge og kommutativitet").
-- The status itself is ordered by the booking lifecycle (PENDING_PAYMENT < CONFIRMED < CHECKED_IN < CANCELLED), not
-- by time, so it needs no timestamp. This column records the newest occurredAt of any booking/flight event applied,
-- which shows how current the snapshot is when something looks wrong.
ALTER TABLE booking_snapshot ADD COLUMN event_occurred_at TIMESTAMPTZ;
