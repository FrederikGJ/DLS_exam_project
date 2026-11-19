package dk.airport.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Map;

/**
 * Ticket 12: a serverless function run by a KEDA ScaledJob - one Kubernetes Job (one pod) per message.
 * <p>
 * No HTTP server, no Spring, no loop: start, take ONE message from the {@code notifications} queue, "send" the mail
 * (written to stdout, i.e. the pod log), acknowledge it, exit. KEDA starts a new Job for the next message.
 * <ul>
 *   <li>{@code once} (default): handle one message. Exit 0 when done or when the queue is already empty (another Job
 *       took the message). A message that cannot be turned into a mail is rejected without requeue - RabbitMQ moves
 *       it to {@code notification-job.dlq} - and the Job exits 1, so it shows up as Failed in {@code kubectl get jobs}.</li>
 *   <li>{@code setup}: only declare the queue, its bindings and DLQ, then exit. Run once at deploy time, because with
 *       zero Jobs running nobody would declare the queue: events would be dropped and KEDA would have nothing to count.</li>
 * </ul>
 * Delivery is at-least-once: if a pod dies between "sending" and the ack, the message is delivered again and the
 * mail is sent twice. Accepted - the function is stateless on purpose (see docs/events.md).
 */
public final class NotificationJob {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;

    private final ConnectionFactory connectionFactory;
    private final String queue;
    private final PrintStream out;
    private final ObjectMapper objectMapper = new ObjectMapper();

    NotificationJob(ConnectionFactory connectionFactory, String queue, PrintStream out) {
        this.connectionFactory = connectionFactory;
        this.queue = queue;
        this.out = out;
    }

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(env.getOrDefault("RABBITMQ_HOST", "localhost"));
        factory.setPort(Integer.parseInt(env.getOrDefault("RABBITMQ_PORT", "5672")));
        factory.setUsername(env.getOrDefault("RABBITMQ_USERNAME", "airport"));
        factory.setPassword(env.getOrDefault("RABBITMQ_PASSWORD", "airport"));
        factory.setConnectionTimeout(10_000);

        String mode = args.length > 0 ? args[0] : "once";
        NotificationJob job = new NotificationJob(factory, env.getOrDefault("NOTIFICATIONS_QUEUE", "notifications"), System.out);
        System.exit("setup".equals(mode) ? job.setup() : job.runOnce());
    }

    int setup() {
        try (Connection connection = connectionFactory.newConnection("notification-job-setup");
             Channel channel = connection.createChannel()) {
            Topology.declare(channel, queue);
            log("Declared queue %s bound to %s on %s (DLQ %s)", queue, Topology.ROUTING_KEYS, Topology.EXCHANGE,
                    Topology.DEAD_LETTER_QUEUE);
            return EXIT_OK;
        } catch (Exception e) {
            log("Setup failed: %s", e);
            return EXIT_FAILED;
        }
    }

    int runOnce() {
        try (Connection connection = connectionFactory.newConnection("notification-job");
             Channel channel = connection.createChannel()) {
            Topology.declare(channel, queue);

            GetResponse message = channel.basicGet(queue, false);
            if (message == null) {
                log("Queue %s is empty - nothing to do (another Job took the message)", queue);
                return EXIT_OK;
            }
            long tag = message.getEnvelope().getDeliveryTag();
            String eventId = "?";
            String eventType = message.getEnvelope().getRoutingKey();
            try {
                JsonNode envelope = objectMapper.readTree(message.getBody());
                eventId = envelope.path("eventId").asText("?");
                eventType = envelope.path("eventType").asText(eventType);
                MailFormatter.Mail mail = MailFormatter.format(envelope);
                log("Sending mail for %s eventId=%s%s", eventType, eventId, message.getEnvelope().isRedeliver() ? " (redelivered)" : "");
                out.println("----- MAIL -----");
                out.println(mail.render());
                out.println("----------------");
                channel.basicAck(tag, false);
                log("Mail sent, message acknowledged - exiting");
                return EXIT_OK;
            } catch (Exception e) {
                channel.basicNack(tag, false, false);   // no requeue -> dead-letter exchange -> notification-job.dlq
                log("Could not handle %s eventId=%s: %s - message moved to %s", eventType, eventId, e.getMessage(),
                        Topology.DEAD_LETTER_QUEUE);
                return EXIT_FAILED;
            }
        } catch (Exception e) {
            log("Could not reach RabbitMQ: %s", e);
            return EXIT_FAILED;   // the message (if any) stays on the queue, KEDA starts a new Job
        }
    }

    private void log(String format, Object... args) {
        out.println(Instant.now() + " notification-job " + String.format(format, args));
    }
}
