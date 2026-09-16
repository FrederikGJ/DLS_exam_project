package dk.airport.notification.mail;

/**
 * A rendered e-mail. {@code eventId} travels along so a real mail provider can use it as idempotency key: the queue
 * delivers at-least-once, so the same event can be rendered twice.
 */
public record Mail(String eventId, String eventType, String to, String subject, String body) {}
