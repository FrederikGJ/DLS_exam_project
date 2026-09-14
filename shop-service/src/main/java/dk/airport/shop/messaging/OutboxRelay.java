package dk.airport.shop.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

/**
 * Moves rows from {@code outbox_event} to RabbitMQ.
 * <pre>
 *  every poll-interval (one active relay per service, guarded by a Postgres advisory lock):
 *    SELECT oldest unpublished rows (FOR UPDATE)
 *    send them to airport.events and wait for publisher confirms
 *    ok   -> published_at = now()
 *    fail -> attempts++, last_error; rows stay pending and are retried next poll
 * </pre>
 * A row is only marked as published after the broker has confirmed it, so delivery is at-least-once: if the
 * relay dies between confirm and commit the row is sent again with the same {@code eventId}, and the
 * consumer's {@code processed_event} table drops the duplicate. Order per producer is preserved because
 * there is one active relay, rows go out by {@code id}, and a failed batch is retried as a whole.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Arbitrary constant. Each service owns its database, so one lock key per service is enough. */
    static final long LOCK_KEY = 0x0A1B0B0AL;

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final EntityManager entityManager;
    private final OutboxProperties props;
    private final String exchange;

    public OutboxRelay(OutboxEventRepository outbox, RabbitTemplate rabbitTemplate, EntityManager entityManager,
                       OutboxProperties props, MeterRegistry meterRegistry,
                       @Value("${app.messaging.exchange}") String exchange) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.entityManager = entityManager;
        this.props = props;
        this.exchange = exchange;
        Gauge.builder("outbox.pending", outbox, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Events written to the outbox but not yet confirmed by RabbitMQ")
                .strongReference(true)
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        if (!tryAcquireRelayLock()) {
            return;                                   // another replica is the active relay right now
        }
        List<OutboxEvent> batch = outbox.findPendingBatch(props.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        try {
            rabbitTemplate.invoke(ops -> {
                for (OutboxEvent event : batch) {
                    ops.send(exchange, event.getEventType(), toMessage(event));
                }
                ops.waitForConfirmsOrDie(props.confirmTimeoutMs());   // throws on nack or timeout
                return null;
            });
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            for (OutboxEvent event : batch) {
                event.markPublished(now);
                log.info("Published event {} eventId={}", event.getEventType(), event.getEventId());
            }
        } catch (AmqpException ex) {
            batch.forEach(event -> event.recordFailure(ex.getMessage()));
            int attempts = batch.get(0).getAttempts();
            // The broker being down produces one failure per poll; log the first and then every 20th
            // (~10 s at the default interval) so the log stays readable while the outbox keeps the count.
            if (attempts == 1 || attempts % 20 == 0) {
                log.warn("Outbox: {} event(s) could not be published (attempt {}), will retry: {}",
                        batch.size(), attempts, ex.getMessage());
            } else {
                log.debug("Outbox: {} event(s) could not be published (attempt {}): {}",
                        batch.size(), attempts, ex.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanup() {
        int deleted = outbox.deletePublishedBefore(OffsetDateTime.now(ZoneOffset.UTC).minus(props.retention()));
        if (deleted > 0) {
            log.info("Outbox: deleted {} published event(s) older than {}", deleted, props.retention());
        }
    }

    /**
     * Transaction-scoped advisory lock: released automatically at commit/rollback. With several replicas
     * only one of them gets past this point per poll, which is what keeps events in order.
     */
    private boolean tryAcquireRelayLock() {
        Object locked = entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock(:key)")
                .setParameter("key", LOCK_KEY)
                .getSingleResult();
        return Boolean.TRUE.equals(locked);
    }

    /** Same AMQP properties the old direct publisher used, so consumers see no difference. */
    private static Message toMessage(OutboxEvent event) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.getEventId());
        properties.setType(event.getEventType());
        properties.setTimestamp(Date.from(event.getCreatedAt().toInstant()));
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties);
    }
}
