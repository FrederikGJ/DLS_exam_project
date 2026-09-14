package dk.airport.payment.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Tuning knobs for {@link OutboxRelay}; see {@code app.outbox.*} in application.yml. */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        /** How often the relay looks for unpublished rows (also the worst-case added latency). */
        @DefaultValue("500") long pollIntervalMs,
        /** Max rows sent per poll; a batch is confirmed and marked as a whole. */
        @DefaultValue("100") int batchSize,
        /** How long to wait for the broker to confirm a batch before treating it as failed. */
        @DefaultValue("5000") long confirmTimeoutMs,
        /** Published rows older than this are deleted by the cleanup job. */
        @DefaultValue("7d") Duration retention,
        /** How often the cleanup job runs. */
        @DefaultValue("3600000") long cleanupIntervalMs) {
}
