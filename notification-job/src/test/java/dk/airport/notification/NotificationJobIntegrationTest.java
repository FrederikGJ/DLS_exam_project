package dk.airport.notification;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Ticket 12: the function against a real RabbitMQ - one run handles exactly one message. */
@Testcontainers
class NotificationJobIntegrationTest {

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String QUEUE = "notifications";

    ConnectionFactory factory;
    ByteArrayOutputStream output;
    NotificationJob job;

    @BeforeEach
    void setUp() throws Exception {
        factory = new ConnectionFactory();
        factory.setHost(rabbit.getHost());
        factory.setPort(rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        output = new ByteArrayOutputStream();
        job = new NotificationJob(factory, QUEUE, new PrintStream(output, true, StandardCharsets.UTF_8));
        assertThat(job.setup()).isZero();
        try (Connection c = factory.newConnection(); Channel ch = c.createChannel()) {
            ch.queuePurge(QUEUE);
            ch.queuePurge(Topology.DEAD_LETTER_QUEUE);
        }
    }

    @Test
    void oneRunHandlesExactlyOneMessageAndExitsZero() throws Exception {
        publish("booking.confirmed", "AAAAAA");
        publish("booking.confirmed", "BBBBBB");

        assertThat(job.runOnce()).isZero();

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("To: anna@example.com").contains("Booking AAAAAA is confirmed");
        assertThat(messageCount(QUEUE)).isEqualTo(1);   // the second message waits for the next Job
    }

    @Test
    void emptyQueueExitsZero() {
        assertThat(job.runOnce()).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("is empty");
    }

    @Test
    void eventsOnTheExchangeAreRoutedToTheQueueOnlyForTheBoundTypes() throws Exception {
        publish("booking.created", "CCCCCC");    // not bound: no mail for created bookings
        publish("booking.cancelled", "CCCCCC");
        assertThat(messageCount(QUEUE)).isEqualTo(1);
    }

    @Test
    void badMessageGoesToTheDlqAndExitsOne() throws Exception {
        try (Connection c = factory.newConnection(); Channel ch = c.createChannel()) {
            ch.basicPublish(Topology.EXCHANGE, "booking.confirmed", MessageProperties.PERSISTENT_TEXT_PLAIN,
                    "not json".getBytes(StandardCharsets.UTF_8));
        }
        awaitCount(QUEUE, 1);

        assertThat(job.runOnce()).isEqualTo(1);

        awaitCount(Topology.DEAD_LETTER_QUEUE, 1);
        assertThat(messageCount(QUEUE)).isZero();
    }

    // ------------------------------------------------------------ helpers

    private void publish(String type, String ref) throws Exception {
        String json = """
                {"eventId":"%s-%s","eventType":"%s","occurredAt":"2026-11-19T10:00:00Z","producer":"booking-service",
                 "payload":{"bookingReference":"%s","flightNumber":"SK1501","departureTime":"2026-12-24T14:00:00Z",
                 "seatNumber":"12C","price":899.00,"currency":"DKK","status":"CONFIRMED",
                 "passenger":{"firstName":"Anna","lastName":"Jensen","email":"anna@example.com"}}}"""
                .formatted(type, ref, type, ref);
        try (Connection c = factory.newConnection(); Channel ch = c.createChannel()) {
            ch.basicPublish(Topology.EXCHANGE, type, MessageProperties.PERSISTENT_TEXT_PLAIN, json.getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(200);
    }

    private long messageCount(String queue) throws Exception {
        try (Connection c = factory.newConnection(); Channel ch = c.createChannel()) {
            return ch.messageCount(queue);
        }
    }

    private void awaitCount(String queue, long expected) throws Exception {
        for (int i = 0; i < 50 && messageCount(queue) != expected; i++) {
            Thread.sleep(100);
        }
        assertThat(messageCount(queue)).isEqualTo(expected);
    }
}
