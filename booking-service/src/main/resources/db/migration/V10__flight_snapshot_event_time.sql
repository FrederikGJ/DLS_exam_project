-- Ticket 10: commutative flight.* handlers.
-- occurredAt of the newest flight event applied to each snapshot field. An event older than the stored
-- value is ignored, so the end state does not depend on the order the events arrive in.
-- Two columns (not one), because flight.status.changed and flight.gate.changed update different fields:
-- an old status change arriving after a newer gate change must still be allowed to set the status.
-- Version 10 leaves V3-V9 free for migrations on the other ticket branches.
ALTER TABLE booking ADD COLUMN flight_status_event_at TIMESTAMPTZ;
ALTER TABLE booking ADD COLUMN gate_event_at          TIMESTAMPTZ;
