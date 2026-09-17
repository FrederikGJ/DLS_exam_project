package dk.airport.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.booking.messaging.EventEnvelope;
import dk.airport.booking.query.BookingOverviewRepository;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.service.FlightClient;
import dk.airport.booking.service.FlightSeatInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The two compensations of the booking saga (dev plan DP-32), against real PostgreSQL + RabbitMQ: a booking that is
 * not paid within the payment timeout (3 s here, 15 min by default) is cancelled and its seat freed, and a payment
 * that arrives for a booking that is already cancelled is answered with booking.payment.rejected, which makes
 * payment-service refund it (tested there).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.booking.payment-timeout=3s",
    "app.booking.payment-timeout-check-interval-ms=500"
})
@AutoConfigureHttpGraphQlTester
@Import(TestTokens.class)
@Testcontainers
class SagaCompensationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String TEST_QUEUE = "test.saga-booking-events";
    static final List<EventEnvelope> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;

    static final String CREATE = """
            mutation($flightId: ID!, $seat: String!) {
              createBooking(flightId: $flightId, seatNumber: $seat, passenger: {
                firstName: "Sara", lastName: "Saga", email: "sara@example.com", passportNumber: "P3141592" }) {
                bookingReference status createdAt paymentDueAt
              }
            }""";

    @Autowired HttpGraphQlTester graphQlTester;
    GraphQlTester asPassenger;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired BookingRepository bookings;
    @Autowired BookingOverviewRepository overviews;
    @MockitoBean FlightClient flightClient;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory,
                                  @Autowired ObjectMapper objectMapper) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(TEST_QUEUE, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("airport.events")).with("booking.#"));
        testListener = new SimpleMessageListenerContainer(connectionFactory);
        testListener.setQueueNames(TEST_QUEUE);
        testListener.setMessageListener(message -> {
            try {
                RECEIVED.add(objectMapper.readValue(message.getBody(), EventEnvelope.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        testListener.start();
    }

    @AfterAll
    static void stopTestListener() {
        if (testListener != null) {
            testListener.stop();
        }
    }

    @BeforeEach
    void setUp() {
        asPassenger = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build();
        when(flightClient.fetchFlightSeat(anyLong(), any())).thenAnswer(inv -> new FlightSeatInfo(
                inv.getArgument(0), "SK1501", OffsetDateTime.now().plusDays(3), "A12", "SCHEDULED", "DKK",
                inv.getArgument(1), "ECONOMY", true, new BigDecimal("899.00")));
    }

    @Test
    void unpaidBookingIsCancelledAfterThePaymentTimeoutAndItsSeatIsFreed() {
        GraphQlTester.Response unpaid = asPassenger.document(CREATE)
                .variable("flightId", 201).variable("seat", "1A").execute();
        String unpaidRef = unpaid.path("createBooking.bookingReference").entity(String.class).get();
        OffsetDateTime createdAt = OffsetDateTime.parse(
                unpaid.path("createBooking.createdAt").entity(String.class).get());
        OffsetDateTime dueAt = OffsetDateTime.parse(
                unpaid.path("createBooking.paymentDueAt").entity(String.class).get());
        assertThat(Duration.between(createdAt, dueAt)).isEqualTo(Duration.ofSeconds(3));

        String paidRef = asPassenger.document(CREATE).variable("flightId", 201).variable("seat", "1B").execute()
                .path("createBooking.bookingReference").entity(String.class).get();
        publish(UUID.randomUUID().toString(), "payment.completed", Map.of("paymentId", 6001,
                "bookingReference", paidRef, "amount", 899.00, "currency", "DKK", "cardLast4", "4242"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(bookings.findByBookingReference(unpaidRef).orElseThrow().getStatus().name())
                    .isEqualTo("CANCELLED");
            assertThat(bookings.findByBookingReference(unpaidRef).orElseThrow().getCancellationReason())
                    .isEqualTo("Payment not received within 3 seconds");
        });
        EventEnvelope cancelled = awaitEvent(forRef(unpaidRef, "booking.cancelled"));
        assertThat(cancelled.payload().path("reason").asText()).isEqualTo("Payment not received within 3 seconds");
        assertThat(overviews.findByBookingReference(unpaidRef).orElseThrow().getCancellationReason())
                .isEqualTo("Payment not received within 3 seconds");

        // the paid booking is untouched by the job, also long after its own deadline
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6)).until(() ->
                bookings.findByBookingReference(paidRef).orElseThrow().getStatus().name().equals("CONFIRMED"));
        assertThat(RECEIVED.stream().filter(forRef(paidRef, "booking.cancelled"))).isEmpty();
        asPassenger.document("query($r: String!) { bookingByReference(reference: $r) { paymentDueAt } }")
                .variable("r", paidRef).execute()
                .path("bookingByReference.paymentDueAt").valueIsNull();

        // the seat of the expired booking can be booked again
        asPassenger.document(CREATE).variable("flightId", 201).variable("seat", "1A").execute()
                .path("createBooking.status").entity(String.class).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void paymentArrivingAfterCancellationIsRejectedSoItGetsRefunded() {
        String ref = asPassenger.document(CREATE).variable("flightId", 202).variable("seat", "2A").execute()
                .path("createBooking.bookingReference").entity(String.class).get();
        asPassenger.document("mutation($r: String!) { cancelBooking(reference: $r) { status cancellationReason } }")
                .variable("r", ref).execute()
                .path("cancelBooking.status").entity(String.class).isEqualTo("CANCELLED")
                .path("cancelBooking.cancellationReason").entity(String.class).isEqualTo("Cancelled by passenger");

        // the payment was made before the cancellation but reaches booking-service after it - and is redelivered
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> completed = Map.of("paymentId", 7001, "bookingReference", ref, "amount", 899.00,
                "currency", "DKK", "cardLast4", "4242");
        publish(eventId, "payment.completed", completed);
        publish(eventId, "payment.completed", completed);

        EventEnvelope rejected = awaitEvent(forRef(ref, "booking.payment.rejected"));
        assertThat(rejected.payload().path("paymentId").asLong()).isEqualTo(7001L);
        assertThat(rejected.payload().path("amount").decimalValue()).isEqualByComparingTo("899.00");
        assertThat(rejected.payload().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(rejected.payload().path("reason").asText()).contains("Cancelled by passenger");

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() ->
                RECEIVED.stream().filter(forRef(ref, "booking.payment.rejected")).count() == 1);
        assertThat(RECEIVED.stream().filter(forRef(ref, "booking.confirmed"))).isEmpty();
        assertThat(bookings.findByBookingReference(ref).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
        // Min booking shows the payment; payment-service's payment.refunded will turn it into REFUNDED
        assertThat(overviews.findByBookingReference(ref).orElseThrow().getPayments())
                .singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("COMPLETED"));
    }

    // ------------------------------------------------------------------ helpers

    private static Predicate<EventEnvelope> forRef(String reference, String type) {
        return e -> e.eventType().equals(type) && e.payload().path("bookingReference").asText().equals(reference);
    }

    private EventEnvelope awaitEvent(Predicate<EventEnvelope> filter) {
        await().atMost(Duration.ofSeconds(15)).until(() -> RECEIVED.stream().anyMatch(filter));
        return RECEIVED.stream().filter(filter).findFirst().orElseThrow();
    }

    private void publish(String eventId, String type, Map<String, Object> payload) {
        try {
            EventEnvelope env = new EventEnvelope(eventId, type, OffsetDateTime.now(ZoneOffset.UTC), "payment-service",
                    objectMapper.valueToTree(payload));
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
