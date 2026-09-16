package dk.airport.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Delivery;
import dk.airport.notification.mail.Mail;
import dk.airport.notification.mail.MailRenderer;
import dk.airport.notification.mail.MailSender;
import dk.airport.notification.mail.MalformedEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The consumer loop: one channel, prefetch 1, manual acknowledgements.
 * <pre>
 *  declare topology (idempotent) -> basicQos(1) -> basicConsume(autoAck=false)
 *  until MAX_MESSAGES deliveries are handled, or no delivery arrives for IDLE_TIMEOUT_MS:
 *    booking event with a template  -> render, send (log)  -> basicAck
 *    event type without a template  -> log "skipping"      -> basicAck   (e.g. a future booking.x.v2)
 *    malformed / unrenderable event -> basicNack(requeue=false) -> notification-job.dlq
 *  basicCancel, close the channel -> JobResult
 * </pre>
 * Delivery is at-least-once: a message is acknowledged only after its mail has been handed to the MailSender. If the
 * job dies in between (pod killed, connection lost), RabbitMQ delivers the message again and the mail is rendered
 * twice - {@link Mail#eventId()} is the idempotency key a real mail provider would deduplicate on.
 * <p>
 * Threading: the amqp-client dispatch thread only puts deliveries into an in-memory inbox; every channel operation
 * (ack, nack, cancel) happens on the calling thread. With prefetch 1 the inbox never holds more than one delivery.
 * A MailSender failure is not caught: the loop stops without acknowledging, the message goes back to the queue when
 * the channel closes, and the job exits non-zero so the next run retries it.
 */
public final class NotificationJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationJob.class);

    private final JobConfig config;
    private final MailRenderer renderer;
    private final MailSender sender;

    public NotificationJob(JobConfig config, MailRenderer renderer, MailSender sender) {
        this.config = config;
        this.renderer = renderer;
        this.sender = sender;
    }

    /**
     * Drains the queue once.
     *
     * @param connection an open connection; the caller closes it
     * @return counters and the reason the loop stopped
     * @throws IOException if a declaration is refused, the consumer is cancelled or the connection is lost
     * @throws InterruptedException if the thread is interrupted while waiting for a delivery
     */
    public JobResult run(Connection connection) throws IOException, InterruptedException {
        Channel channel = connection.createChannel();
        try {
            return consume(channel);
        } finally {
            closeQuietly(channel);
        }
    }

    private JobResult consume(Channel channel) throws IOException, InterruptedException {
        Topology.declare(channel, config.queue());
        channel.basicQos(1);

        BlockingQueue<Signal> inbox = new LinkedBlockingQueue<>();
        String consumerTag = channel.basicConsume(config.queue(), false,
                (tag, delivery) -> inbox.add(new Received(delivery)),
                tag -> inbox.add(new Stopped("consumer was cancelled by the broker (queue deleted?)")),
                (tag, signal) -> inbox.add(new Stopped("connection lost: " + signal.getMessage())));
        log.info("Consuming from {} (prefetch 1, max {} messages, idle timeout {} ms)",
                config.queue(), config.maxMessages(), config.idleTimeout().toMillis());

        int sent = 0;
        int skipped = 0;
        int rejected = 0;
        int received = 0;
        while (true) {
            Signal signal = inbox.poll(config.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (signal == null) {
                channel.basicCancel(consumerTag);
                return new JobResult(received, sent, skipped, rejected, JobResult.StopReason.IDLE);
            }
            if (signal instanceof Stopped stopped) {
                throw new IOException(stopped.reason());
            }
            Delivery delivery = ((Received) signal).delivery();
            received++;
            Outcome outcome = handle(delivery);
            boolean last = received >= config.maxMessages();
            if (last) {
                // Cancel before acknowledging: the unacknowledged message holds the only prefetch slot, so the
                // broker cannot hand this consumer another message that would then have to be requeued.
                channel.basicCancel(consumerTag);
            }
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            switch (outcome) {
                case SENT -> {
                    channel.basicAck(deliveryTag, false);
                    sent++;
                }
                case SKIPPED -> {
                    channel.basicAck(deliveryTag, false);
                    skipped++;
                }
                default -> {
                    channel.basicNack(deliveryTag, false, false);
                    rejected++;
                }
            }
            if (last) {
                return new JobResult(received, sent, skipped, rejected, JobResult.StopReason.MAX_MESSAGES);
            }
        }
    }

    private Outcome handle(Delivery delivery) {
        String redelivered = delivery.getEnvelope().isRedeliver() ? " (redelivered)" : "";
        Mail mail;
        try {
            JsonNode event = renderer.parse(delivery.getBody());
            Optional<Mail> rendered = renderer.render(event);
            if (rendered.isEmpty()) {
                log.info("Skipping {} eventId={}{}: no mail template for this event type",
                        event.path("eventType").asText(), event.path("eventId").asText(), redelivered);
                return Outcome.SKIPPED;
            }
            mail = rendered.get();
        } catch (MalformedEventException e) {
            log.warn("Rejecting message routingKey={} messageId={}{} -> {}: {}", routingKey(delivery),
                    delivery.getProperties().getMessageId(), redelivered, Topology.DEAD_LETTER_QUEUE, e.getMessage());
            return Outcome.REJECTED;
        } catch (RuntimeException e) {
            // A bug in a template must not turn into a poison message that crashes every run: park it in the DLQ.
            log.error("Rejecting message routingKey={} messageId={}{} -> {}: rendering failed",
                    routingKey(delivery), delivery.getProperties().getMessageId(), redelivered,
                    Topology.DEAD_LETTER_QUEUE, e);
            return Outcome.REJECTED;
        }
        sender.send(mail);
        return Outcome.SENT;
    }

    private static String routingKey(Delivery delivery) {
        return delivery.getEnvelope().getRoutingKey();
    }

    private static void closeQuietly(Channel channel) {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (IOException | TimeoutException e) {
            log.warn("Could not close channel cleanly: {}", e.toString());
        }
    }

    private enum Outcome { SENT, SKIPPED, REJECTED }

    /** What the amqp-client callbacks hand to the loop. */
    private sealed interface Signal permits Received, Stopped {}

    private record Received(Delivery delivery) implements Signal {}

    private record Stopped(String reason) implements Signal {}
}
