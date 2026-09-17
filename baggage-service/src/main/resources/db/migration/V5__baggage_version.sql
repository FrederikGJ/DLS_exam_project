-- Optimistic locking on baggage (dev plan DP-31, see docs/events.md "Rækkefølge og kommutativitet").
-- flight.cancelled sends bags to the return desk on the listener thread while an operator may update the same bag's
-- status over GraphQL or REST. The transaction that read an older version now fails - the listener retries, the
-- operator gets CONFLICT - instead of silently overwriting the other change.
ALTER TABLE baggage ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
