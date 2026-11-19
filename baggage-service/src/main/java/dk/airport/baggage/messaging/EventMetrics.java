package dk.airport.baggage.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Counters for the event flow (dev plan DP-33), exported on {@code /actuator/prometheus}:
 * <ul>
 *   <li>{@code events_published_total{type}} - events confirmed by RabbitMQ (OutboxRelay);</li>
 *   <li>{@code events_consumed_total{type, outcome}} - one per delivery handled by a consumer, with outcome
 *       {@code processed}, {@code duplicate} (eventId already in processed_event) or {@code failed} (every failed
 *       attempt, retries included - a message that fails three times ends in the DLQ).</li>
 * </ul>
 * Only known types become metric labels. Other types are grouped as unknown.
 */
@Component
public class EventMetrics {

    public static final String PROCESSED = "processed";
    public static final String DUPLICATE = "duplicate";
    public static final String FAILED = "failed";
    /** Type used for a message whose envelope could not even be parsed. */
    public static final String UNREADABLE = "unreadable";

    private static final Set<String> EVENT_TYPES = Set.of(
            "flight.created", "flight.status.changed", "flight.gate.changed", "flight.cancelled",
            "booking.created", "booking.confirmed", "booking.cancelled", "booking.checkedin",
            "payment.completed", "payment.failed", "payment.refunded",
            "baggage.registered", "baggage.status.changed", UNREADABLE);

    private final MeterRegistry registry;

    public EventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void published(String eventType) {
        registry.counter("events.published", "type", metricType(eventType)).increment();
    }

    public void consumed(String eventType, String outcome) {
        registry.counter("events.consumed", "type", metricType(eventType), "outcome", outcome).increment();
    }

    private static String metricType(String eventType) {
        return eventType != null && EVENT_TYPES.contains(eventType) ? eventType : "unknown";
    }
}
