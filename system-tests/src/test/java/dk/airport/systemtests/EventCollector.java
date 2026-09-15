package dk.airport.systemtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Collects every event published on the {@code airport.events} exchange (binding {@code #}) with the plain RabbitMQ
 * Java client. The test thereby observes the broker exactly like any other consumer would - no Spring AMQP, no
 * peeking into the services' outbox tables - which is what makes it a system test of the cooperation and not of
 * either service's internals.
 */
final class EventCollector implements AutoCloseable {

    /** Envelope of every event on the exchange (docs/events.md); each service has an identical copy of this record. */
    record Event(String eventId, String eventType, OffsetDateTime occurredAt, String producer, JsonNode payload) {}

    private static final Logger log = LoggerFactory.getLogger(EventCollector.class);
    private static final String QUEUE = "system-tests.events";
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(20);

    private final Connection connection;
    private final List<Event> received = new CopyOnWriteArrayList<>();

    private EventCollector(Connection connection) {
        this.connection = connection;
    }

    /** Connects, declares an exclusive auto-delete queue bound with {@code #} and starts consuming into memory. */
    static EventCollector connect(String host, int port, String username, String password, ObjectMapper objectMapper)
            throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        EventCollector collector = new EventCollector(factory.newConnection("system-tests"));
        collector.subscribe(objectMapper);
        return collector;
    }

    /** Snapshot of everything received so far, in arrival order. */
    List<Event> received() {
        return List.copyOf(received);
    }

    /** Waits until at least {@code expected} matching events have arrived and returns all matching ones. */
    List<Event> awaitEvents(Predicate<Event> filter, int expected) {
        await().atMost(EVENT_TIMEOUT).untilAsserted(() ->
                assertThat(received.stream().filter(filter).count())
                        .as("events matching the filter (received so far: %s)", summary())
                        .isGreaterThanOrEqualTo(expected));
        return received.stream().filter(filter).toList();
    }

    /** Waits for one event of the given type whose payload carries the booking reference. */
    Event awaitEvent(String eventType, String bookingReference) {
        return awaitEvents(e -> e.eventType().equals(eventType)
                && bookingReference.equals(e.payload().path("bookingReference").asText()), 1).getFirst();
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }

    private void subscribe(ObjectMapper objectMapper) throws IOException {
        Channel channel = connection.createChannel();
        // same declaration as the services' RabbitConfig (durable topic exchange), so whoever comes first wins
        channel.exchangeDeclare(Stack.EXCHANGE, BuiltinExchangeType.TOPIC, true);
        channel.queueDeclare(QUEUE, false, true, true, null);
        channel.queueBind(QUEUE, Stack.EXCHANGE, "#");
        channel.basicConsume(QUEUE, true, new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                                       byte[] body) throws IOException {
                Event event = objectMapper.readValue(body, Event.class);
                log.info("event {} eventId={} producer={}", envelope.getRoutingKey(), event.eventId(),
                        event.producer());
                received.add(event);
            }
        });
    }

    private List<String> summary() {
        return received.stream()
                .map(e -> e.eventType() + "(" + e.payload().path("bookingReference").asText() + ")")
                .toList();
    }
}
