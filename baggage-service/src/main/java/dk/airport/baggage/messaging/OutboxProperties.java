package dk.airport.baggage.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning knobs for {@link OutboxRelay}; see {@code app.outbox.*} in application.yml.
 *
 * @param pollIntervalMs how often the relay looks for unpublished rows (also the worst-case added latency)
 * @param batchSize max rows sent per poll; a batch is confirmed and marked as a whole
 * @param confirmTimeoutMs how long to wait for the broker to confirm a batch before treating it as failed
 * @param retention published rows older than this are deleted by the cleanup job
 * @param cleanupIntervalMs how often the cleanup job runs
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @DefaultValue("500") long pollIntervalMs,
        @DefaultValue("100") int batchSize,
        @DefaultValue("5000") long confirmTimeoutMs,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("3600000") long cleanupIntervalMs) {
}
