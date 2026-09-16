-- Idempotent registration (dev plan DP-30): the client sends a key per intended registration (a UUID per form),
-- and a repeated request with the same key - double click, retry after a timeout - finds the bag the first request
-- created instead of creating a second one. NULL for bags registered without a key; UNIQUE allows many NULLs.
ALTER TABLE baggage ADD COLUMN idempotency_key VARCHAR(64);
ALTER TABLE baggage ADD CONSTRAINT uq_baggage_idempotency_key UNIQUE (idempotency_key);
