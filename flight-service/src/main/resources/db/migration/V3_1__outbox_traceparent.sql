-- Preserve trace context across the commit, scheduled relay and any publish retries.
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55);
