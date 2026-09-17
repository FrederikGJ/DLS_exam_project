-- Commutative flight snapshot on bookings (dev plan DP-31, see docs/events.md "Rækkefølge og kommutativitet").
-- occurredAt of the newest flight.* event seen for flight_status and for gate. Status and gate have a timestamp each
-- because flight.gate.changed carries only the gate: a gate change must not be undone by an older status change that
-- still carries the old gate. NULL = only the value fetched from flight-service at booking time: any event applies.
ALTER TABLE booking ADD COLUMN flight_status_changed_at TIMESTAMPTZ;
ALTER TABLE booking ADD COLUMN gate_changed_at TIMESTAMPTZ;

-- Optimistic locking: payment events, flight events and the passenger's own mutations change the same booking row on
-- different threads. A transaction that read an older version now fails (and is retried) instead of silently
-- overwriting the other's change, e.g. a gate change writing PENDING_PAYMENT over a CONFIRMED that committed meanwhile.
ALTER TABLE booking ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
