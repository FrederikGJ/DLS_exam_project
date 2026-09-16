package dk.airport.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import dk.airport.notification.mail.Mail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the whole job ({@link Main#run}) against a real RabbitMQ (Testcontainers). Mails go to a recording
 * MailSender instead of the log so they can be asserted on.
 */
@Testcontainers
class NotificationJobIntegrationTest {

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUEUE = "notifications";

    private final List<Mail> mails = new CopyOnWriteArrayList<>();
    private Connection connection;
    private Channel channel;

    @BeforeEach
    void freshQueues() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbit.getHost());
        factory.setPort(rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        connection = factory.newConnection("test");
        channel = connection.createChannel();
        channel.queueDelete(QUEUE);
        channel.queueDelete(Topology.DEAD_LETTER_QUEUE);
        declareLikeBookingService();
        channel.confirmSelect();
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
    }

    /**
     * The topology as booking-service's RabbitConfig declares it, written out literally. Events published before the
     * job ever ran must be kept, and the job's own declaration must be accepted as identical (otherwise it exits 1).
     */
    private void declareLikeBookingService() throws IOException {
        channel.exchangeDeclare("airport.events", "topic", true, false, Map.of());
        channel.exchangeDeclare("airport.events.dlx", "direct", true, false, Map.of());
        channel.queueDeclare("notification-job.dlq", true, false, false, Map.of());
        channel.queueBind("notification-job.dlq", "airport.events.dlx", "notification-job");
        channel.queueDeclare("notifications", true, false, false, Map.of(
                "x-dead-letter-exchange", "airport.events.dlx",
                "x-dead-letter-routing-key", "notification-job"));
        channel.queueBind("notifications", "airport.events", "booking.#");
    }

    private Map<String, String> env(String... overrides) {
        Map<String, String> env = new HashMap<>(Map.of(
                "RABBITMQ_HOST", rabbit.getHost(),
                "RABBITMQ_PORT", String.valueOf(rabbit.getAmqpPort()),
                "RABBITMQ_USERNAME", rabbit.getAdminUsername(),
                "RABBITMQ_PASSWORD", rabbit.getAdminPassword(),
                "IDLE_TIMEOUT_MS", "1000"));
        for (int i = 0; i < overrides.length; i += 2) {
            env.put(overrides[i], overrides[i + 1]);
        }
        return env;
    }

    /** Publishes like booking-service's OutboxRelay: routing key = eventType, JSON, persistent, confirmed. */
    private void publish(String routingKey, byte[] body) throws Exception {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .messageId(UUID.randomUUID().toString())
                .type(routingKey)
                .build();
        channel.basicPublish("airport.events", routingKey, props, body);
        channel.waitForConfirmsOrDie(5000);
    }

    private void publishBookingEvent(String eventType, String reference) throws Exception {
        ObjectNode event = JSON.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", eventType)
                .put("occurredAt", "2026-09-16T10:15:30.123Z")
                .put("producer", "booking-service");
        ObjectNode payload = event.putObject("payload")
                .put("bookingId", 1)
                .put("bookingReference", reference)
                .put("flightNumber", "SK1501")
                .put("departureTime", "2026-09-20T14:00:00Z")
                .put("seatNumber", "12C")
                .put("price", 899.00)
                .put("currency", "DKK")
                .put("status", "CANCELLED")
                .put("reason", "Cancelled by passenger");
        payload.putObject("passenger").put("firstName", "Anna").put("lastName", "Jensen")
                .put("email", "anna@example.com");
        publish(eventType, JSON.writeValueAsBytes(event));
    }

    private long messageCount(String queue) throws IOException {
        return channel.messageCount(queue);
    }

    @Test
    void drainsTheQueue_rendersTwoMails_deadLettersTheMalformedOne_andExitsZero() throws Exception {
        publishBookingEvent("booking.created", "AAAAA1");
        byte[] malformed = "{this is not json".getBytes(StandardCharsets.UTF_8);
        publish("booking.confirmed", malformed);
        publishBookingEvent("booking.cancelled", "AAAAA2");
        assertThat(messageCount(QUEUE)).isEqualTo(3);

        int exitCode = Main.run(env(), mails::add);

        assertThat(exitCode).isZero();
        assertThat(mails).extracting(Mail::subject).containsExactly(
                "Din booking AAAAA1 er modtaget - afventer betaling",
                "Din booking AAAAA2 er annulleret");
        assertThat(mails.get(1).body()).contains("Årsag: Cancelled by passenger");
        assertThat(mails).extracting(Mail::to).containsOnly("anna@example.com");
        assertThat(messageCount(QUEUE)).isZero();

        await().atMost(Duration.ofSeconds(5)).until(() -> messageCount(Topology.DEAD_LETTER_QUEUE) == 1);
        GetResponse dead = channel.basicGet(Topology.DEAD_LETTER_QUEUE, true);
        assertThat(dead.getBody()).isEqualTo(malformed);
        List<?> deaths = (List<?>) dead.getProps().getHeaders().get("x-death");
        assertThat(((Map<?, ?>) deaths.get(0)).get("reason")).hasToString("rejected");
        assertThat(((Map<?, ?>) deaths.get(0)).get("queue")).hasToString(QUEUE);
    }

    @Test
    void unknownEventType_isAcknowledgedAndSkipped() throws Exception {
        publishBookingEvent("booking.x.v2", "BBBBB1");

        int exitCode = Main.run(env(), mails::add);

        assertThat(exitCode).isZero();
        assertThat(mails).isEmpty();
        assertThat(messageCount(QUEUE)).isZero();
        assertThat(messageCount(Topology.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void stopsAfterMaxMessages_andLeavesTheRestUntouchedForTheNextRun() throws Exception {
        publishBookingEvent("booking.confirmed", "CCCCC1");
        publishBookingEvent("booking.confirmed", "CCCCC2");
        publishBookingEvent("booking.confirmed", "CCCCC3");

        int exitCode = Main.run(env("MAX_MESSAGES", "2"), mails::add);

        assertThat(exitCode).isZero();
        assertThat(mails).extracting(Mail::subject)
                .containsExactly("Din booking CCCCC1 er bekræftet", "Din booking CCCCC2 er bekræftet");
        assertThat(messageCount(QUEUE)).isEqualTo(1);
        // Consumer cancelled before the last ack: the third message was never delivered, so it is not redelivered.
        GetResponse rest = channel.basicGet(QUEUE, true);
        assertThat(rest.getEnvelope().isRedeliver()).isFalse();
        assertThat(new String(rest.getBody(), StandardCharsets.UTF_8)).contains("CCCCC3");
    }

    @Test
    void emptyQueue_exitsZeroAfterTheIdleTimeout() {
        long start = System.nanoTime();

        int exitCode = Main.run(env("IDLE_TIMEOUT_MS", "300"), mails::add);

        assertThat(exitCode).isZero();
        assertThat(mails).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void queueDeclaredWithDifferentArguments_exitsNonZero() throws Exception {
        channel.queueDeclare("notifications-without-dlx", true, false, false, null);

        int exitCode = Main.run(env("NOTIFICATION_QUEUE", "notifications-without-dlx"), mails::add);

        assertThat(exitCode).isEqualTo(Main.EXIT_BROKER_FAILURE);
        channel.queueDelete("notifications-without-dlx");
    }

    @Test
    void unreachableBroker_exitsNonZero() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        int exitCode = Main.run(env("RABBITMQ_HOST", "127.0.0.1", "RABBITMQ_PORT", String.valueOf(closedPort)),
                mails::add);

        assertThat(exitCode).isEqualTo(Main.EXIT_BROKER_FAILURE);
    }

    @Test
    void wrongCredentials_exitNonZero() {
        int exitCode = Main.run(env("RABBITMQ_PASSWORD", "wrong"), mails::add);

        assertThat(exitCode).isEqualTo(Main.EXIT_BROKER_FAILURE);
    }
}
