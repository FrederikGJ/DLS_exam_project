-- Commutative seat handler (dev plan DP-31, see docs/events.md "Rækkefølge og kommutativitet").
-- occurredAt of the booking event that last set is_available. booking.confirmed (taken) and booking.cancelled (free)
-- for the same seat are applied last-writer-wins on this timestamp, so a late or redelivered older event can never
-- undo a newer one. NULL = never changed by an event (seed data / new flight): any event may set it.
ALTER TABLE seat ADD COLUMN availability_changed_at TIMESTAMPTZ;
