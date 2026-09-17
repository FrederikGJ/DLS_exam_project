package dk.airport.booking.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row per event waiting to be (or already) published to RabbitMQ.
 * <p>
 * Written by {@link EventPublisher} inside the business transaction, read and marked as published by
 * {@link OutboxRelay}. The {@code payload} column holds the serialized {@link EventEnvelope} exactly as it
 * is sent on the wire, so the relay never has to re-serialize anything.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    /** W3C trace context of the publishing span, sent as the {@code traceparent} header (null: no span). */
    @Column(name = "traceparent", length = 55)
    private String traceparent;

    protected OutboxEvent() {}

    public OutboxEvent(String eventId, String eventType, String payload, OffsetDateTime createdAt,
                       String traceparent) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.traceparent = traceparent;
    }

    /**
     * Called by the relay once the broker has confirmed the message. {@code attempts} and {@code lastError}
     * are kept as they were, so a row that needed retries still shows why.
     */
    public void markPublished(OffsetDateTime at) {
        this.publishedAt = at;
    }

    /** Called by the relay when publishing failed; the row stays pending and is retried on the next poll. */
    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getTraceparent() { return traceparent; }
}
