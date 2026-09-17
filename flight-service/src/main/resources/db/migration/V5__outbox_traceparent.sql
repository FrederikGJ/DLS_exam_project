-- Trace context of an event (dev plan DP-33, see docs/architecture.md "Observability").
-- EventPublisher stores the W3C traceparent of the span the event was published in (the HTTP request or the consumed
-- event that caused it); OutboxRelay sends it as the AMQP header `traceparent`. The consumer then continues the same
-- trace, although the message leaves later and from the relay's thread. NULL when there was no span.
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55);
