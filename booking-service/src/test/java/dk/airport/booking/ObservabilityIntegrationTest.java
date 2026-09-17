package dk.airport.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.booking.messaging.EventEnvelope;
import dk.airport.booking.messaging.OutboxEvent;
import dk.airport.booking.messaging.OutboxEventRepository;
import dk.airport.booking.service.FlightClient;
import dk.airport.booking.service.FlightSeatInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Observability of the event flow (dev plan DP-33), with tracing and metrics switched on as in production: a consumed
 * event continues the trace of its {@code traceparent} header - the trace id is in the handler's log lines (MDC), is
 * stored with the event it publishes in the outbox and leaves booking-service in that event's header - and the
 * {@code events.consumed}/{@code events.published} counters follow what happened.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@AutoConfigureObservability
@Import(TestTokens.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String TEST_QUEUE = "test.observability-booking-events";
    /** Messages published by booking-service (booking.#), with their AMQP properties. */
    static final List<Message> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired OutboxEventRepository outbox;
    @Autowired MeterRegistry meterRegistry;
    @MockitoBean FlightClient flightClient;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(TEST_QUEUE, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("airport.events")).with("booking.#"));
        testListener = new SimpleMessageListenerContainer(connectionFactory);
        testListener.setQueueNames(TEST_QUEUE);
        testListener.setMessageListener(RECEIVED::add);
        testListener.start();
    }

    @AfterAll
    static void stopTestListener() {
        if (testListener != null) {
            testListener.stop();
        }
    }

    @Test
    void consumedEventContinuesItsTraceInLogsOutboxAndTheNextEvent(CapturedOutput output) throws Exception {
        when(flightClient.fetchFlightSeat(anyLong(), any())).thenAnswer(inv -> new FlightSeatInfo(
                inv.getArgument(0), "SK1501", OffsetDateTime.now().plusDays(3), "A12", "SCHEDULED", "DKK",
                inv.getArgument(1), "ECONOMY", true, new BigDecimal("899.00")));
        String reference = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build()
                .document("""
                        mutation {
                          createBooking(flightId: 301, seatNumber: "4D", passenger: {
                            firstName: "Tove", lastName: "Trace", email: "tove@example.com",
                            passportNumber: "P6021023" }) { bookingReference }
                        }""")
                .execute().path("createBooking.bookingReference").entity(String.class).get();

        // the HTTP request had a trace of its own, and booking.created carries it
        OutboxEvent created = await().atMost(Duration.ofSeconds(10))
                .until(() -> outboxRow("booking.created", reference), row -> row != null);
        assertThat(created.getTraceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");

        double processedBefore = count("events.consumed", "payment.completed", "processed");
        double duplicateBefore = count("events.consumed", "payment.completed", "duplicate");
        double publishedBefore = count("events.published", "booking.confirmed", null);

        // payment-service's event arrives with its trace context, and is delivered twice
        String traceId = HexFormat.of().formatHex(randomBytes(16));
        String traceparent = "00-" + traceId + "-" + HexFormat.of().formatHex(randomBytes(8)) + "-01";
        String eventId = UUID.randomUUID().toString();
        publish(eventId, traceparent, Map.of("paymentId", 1, "bookingReference", reference, "amount", 899.00,
                "currency", "DKK", "cardLast4", "4242"));
        publish(eventId, traceparent, Map.of("paymentId", 1, "bookingReference", reference, "amount", 899.00,
                "currency", "DKK", "cardLast4", "4242"));

        // 1. the trace id is in booking-service's log lines for the event (MDC)
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(line -> line.contains("Booking " + reference + " confirmed after payment")))
                .singleElement().asString().contains(traceId));

        // 2. booking.confirmed is stored with a traceparent of the same trace ...
        OutboxEvent confirmed = await().atMost(Duration.ofSeconds(10))
                .until(() -> outboxRow("booking.confirmed", reference), row -> row != null);
        assertThat(confirmed.getTraceparent()).startsWith("00-" + traceId + "-");

        // 3. ... and leaves booking-service with it as the AMQP header, so the next consumer continues the trace
        Message sent = await().atMost(Duration.ofSeconds(10)).until(() -> RECEIVED.stream()
                .filter(m -> "booking.confirmed".equals(m.getMessageProperties().getType())
                        && new String(m.getBody()).contains(reference))
                .findFirst().orElse(null), m -> m != null);
        assertThat(sent.getMessageProperties().<String>getHeader("traceparent")).startsWith("00-" + traceId + "-");

        // 4. counters: one processed, one duplicate, one confirmed event published
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(count("events.consumed", "payment.completed", "processed")).isEqualTo(processedBefore + 1);
            assertThat(count("events.consumed", "payment.completed", "duplicate")).isEqualTo(duplicateBefore + 1);
            assertThat(count("events.published", "booking.confirmed", null)).isEqualTo(publishedBefore + 1);
        });
    }

    @Test
    void unreadableMessageIsCountedAsFailed() {
        double before = count("events.consumed", "unreadable", "failed");

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("airport.events", "payment.completed", new Message("not json".getBytes(), props));

        // three attempts (listener retry), then the message goes to booking-service.dlq
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("events.consumed", "unreadable", "failed")).isEqualTo(before + 3));
    }

    // ------------------------------------------------------------------ helpers

    private OutboxEvent outboxRow(String type, String reference) {
        return outbox.findAll().stream()
                .filter(e -> e.getEventType().equals(type) && e.getPayload().contains(reference))
                .findFirst().orElse(null);
    }

    private double count(String name, String type, String outcome) {
        Counter counter = outcome == null
                ? meterRegistry.find(name).tag("type", type).counter()
                : meterRegistry.find(name).tag("type", type).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        ThreadLocalRandom.current().nextBytes(bytes);
        bytes[0] |= 1;   // never all zero, which W3C calls invalid
        return bytes;
    }

    private void publish(String eventId, String traceparent, Map<String, Object> payload) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, "payment.completed", OffsetDateTime.now(ZoneOffset.UTC),
                "payment-service", objectMapper.valueToTree(payload));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("traceparent", traceparent);
        rabbitTemplate.send("airport.events", "payment.completed", new Message(objectMapper.writeValueAsBytes(env),
                props));
    }
}
