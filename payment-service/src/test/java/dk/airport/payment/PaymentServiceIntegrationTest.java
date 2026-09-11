package dk.airport.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.payment.messaging.EventEnvelope;
import dk.airport.payment.messaging.ProcessedEventRepository;
import dk.airport.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end test against real PostgreSQL + RabbitMQ (Testcontainers):
 * Flyway migrations, GraphQL pay/refund, event publishing and idempotent consumption of booking.cancelled.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String TEST_QUEUE = "test.payment-events";
    static final String REF_OK = "K7Q2ZP";
    static final String REF_FAIL = "F41L00";

    /** Every event published on airport.events with routing key payment.# ends up here. */
    static final List<EventEnvelope> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;

    @Autowired GraphQlTester graphQlTester;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory, @Autowired ObjectMapper objectMapper) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(TEST_QUEUE, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("airport.events")).with("payment.#"));

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

    private static final String PAY = """
            mutation($ref: String!, $amount: BigDecimal!, $card: String!, $expiry: String!, $cvv: String!) {
              pay(bookingReference: $ref, amount: $amount, cardNumber: $card, expiry: $expiry, cvv: $cvv) {
                id bookingReference amount currency cardLast4 status failureReason createdAt
              }
            }""";

    @Test
    @Order(1)
    void successfulPaymentIsCompletedAndPublished() {
        GraphQlTester.Response response = graphQlTester.document(PAY)
                .variable("ref", REF_OK).variable("amount", 899.00)
                .variable("card", "4242 4242 4242 4242").variable("expiry", "12/30").variable("cvv", "123")
                .execute();
        response.path("pay.status").entity(String.class).isEqualTo("COMPLETED")
                .path("pay.cardLast4").entity(String.class).isEqualTo("4242")
                .path("pay.currency").entity(String.class).isEqualTo("DKK")
                .path("pay.failureReason").valueIsNull();
        Long paymentId = response.path("pay.id").entity(Long.class).get();

        List<EventEnvelope> events = awaitEvents(e -> e.eventType().equals("payment.completed")
                && e.payload().path("bookingReference").asText().equals(REF_OK), 1);
        EventEnvelope completed = events.get(0);
        assertThat(completed.eventId()).isNotBlank();
        assertThat(completed.producer()).isEqualTo("payment-service");
        assertThat(completed.occurredAt()).isNotNull();
        assertThat(completed.payload().get("paymentId").asLong()).isEqualTo(paymentId);
        assertThat(completed.payload().get("amount").decimalValue()).isEqualByComparingTo("899.00");
        assertThat(completed.payload().get("cardLast4").asText()).isEqualTo("4242");

        // the full card number must never be stored
        Map<String, Object> row = jdbcTemplate.queryForMap("select * from payment where id = ?", paymentId);
        assertThat(row.toString()).doesNotContain("4242424242424242").doesNotContain("4242 4242");
        assertThat(row.get("card_last4")).isEqualTo("4242");
        assertThat(row.keySet()).doesNotContain("card_number", "cvv");
    }

    @Test
    @Order(2)
    void payingTwiceIsRejectedWithAlreadyPaid() {
        graphQlTester.document(PAY)
                .variable("ref", REF_OK).variable("amount", 899.00)
                .variable("card", "4242424242424242").variable("expiry", "12/30").variable("cvv", "123")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "ALREADY_PAID");
                });
    }

    @Test
    @Order(3)
    void declinedCardGivesFailedPaymentAndEvent() {
        graphQlTester.document(PAY)
                .variable("ref", REF_FAIL).variable("amount", 549.00)
                .variable("card", "4111111111110000").variable("expiry", "12/30").variable("cvv", "999")
                .execute()
                .path("pay.status").entity(String.class).isEqualTo("FAILED")
                .path("pay.failureReason").entity(String.class).isEqualTo("Insufficient funds")
                .path("pay.cardLast4").entity(String.class).isEqualTo("0000");

        List<EventEnvelope> events = awaitEvents(e -> e.eventType().equals("payment.failed")
                && e.payload().path("bookingReference").asText().equals(REF_FAIL), 1);
        assertThat(events.get(0).payload().get("failureReason").asText()).isEqualTo("Insufficient funds");
    }

    @Test
    @Order(4)
    void bookingCancelledRefundsCompletedPaymentIdempotently() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("bookingReference", REF_OK, "flightId", 1, "seatNumber", "12C",
                "status", "CANCELLED", "reason", "Cancelled by passenger");
        publish(eventId, "booking.cancelled", payload);
        publish(eventId, "booking.cancelled", payload); // duplicate delivery must be ignored

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(paymentRepository.findByBookingReferenceOrderByCreatedAt(REF_OK))
                        .extracting(p -> p.getStatus().name()).containsExactly("REFUNDED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());

        awaitEvents(e -> e.eventType().equals("payment.refunded")
                && e.payload().path("bookingReference").asText().equals(REF_OK), 1);
        // give a potential (wrong) second refund a moment to appear, then assert exactly one
        Thread.sleep(1000);
        assertThat(RECEIVED.stream().filter(e -> e.eventType().equals("payment.refunded")
                && e.payload().path("bookingReference").asText().equals(REF_OK)).count()).isEqualTo(1);

        // a cancelled booking that was never paid refunds nothing and does not fail
        publish(UUID.randomUUID().toString(), "booking.cancelled", Map.of("bookingReference", "NOPAY1", "status", "CANCELLED"));
        publish(UUID.randomUUID().toString(), "booking.confirmed", Map.of("bookingReference", REF_FAIL, "status", "CONFIRMED"));
        Thread.sleep(1000);
        assertThat(paymentRepository.findByBookingReferenceOrderByCreatedAt(REF_FAIL))
                .extracting(p -> p.getStatus().name()).containsExactly("FAILED");
    }

    @Test
    @Order(5)
    void paymentsByBookingListsHistory() {
        graphQlTester.document("query($ref: String!) { paymentsByBooking(reference: $ref) { id status amount } }")
                .variable("ref", REF_OK)
                .execute()
                .path("paymentsByBooking").entityList(Object.class).hasSize(1)
                .path("paymentsByBooking[0].status").entity(String.class).isEqualTo("REFUNDED");

        graphQlTester.document("query($ref: String!) { paymentsByBooking(reference: $ref) { id status } }")
                .variable("ref", "ZZZZZZ")
                .execute()
                .path("paymentsByBooking").entityList(Object.class).hasSize(0);
    }

    @Test
    @Order(6)
    void refundOfFailedPaymentIsInvalidState() {
        Long failedId = paymentRepository.findByBookingReferenceOrderByCreatedAt(REF_FAIL).get(0).getId();
        graphQlTester.document("mutation($id: ID!) { refund(paymentId: $id) { id status } }")
                .variable("id", failedId)
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "INVALID_STATE");
                });

        graphQlTester.document("mutation { refund(paymentId: 999999) { id } }")
                .execute()
                .errors().satisfy(errors -> assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND"));
    }

    @Test
    @Order(7)
    void manualRefundOfCompletedPaymentWorks() {
        Long id = graphQlTester.document(PAY)
                .variable("ref", "REFUND").variable("amount", 100.00)
                .variable("card", "5555555555554444").variable("expiry", "01/2031").variable("cvv", "0000")
                .execute()
                .path("pay.status").entity(String.class).isEqualTo("COMPLETED")
                .path("pay.id").entity(Long.class).get();

        graphQlTester.document("mutation($id: ID!) { refund(paymentId: $id) { id status } }")
                .variable("id", id)
                .execute()
                .path("refund.status").entity(String.class).isEqualTo("REFUNDED");

        awaitEvents(e -> e.eventType().equals("payment.refunded") && e.payload().path("paymentId").asLong() == id, 1);
    }

    @Test
    @Order(8)
    void invalidInputIsValidationError() {
        graphQlTester.document(PAY)
                .variable("ref", "ABC123").variable("amount", 10.00)
                .variable("card", "4242424242424242").variable("expiry", "2030-12").variable("cvv", "123")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                    assertThat(errors.get(0).getMessage()).contains("expiry");
                });

        graphQlTester.document(PAY)
                .variable("ref", "TOOLONGREF").variable("amount", -1)
                .variable("card", "1234").variable("expiry", "12/30").variable("cvv", "12")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                    assertThat(errors.get(0).getMessage()).contains("bookingReference").contains("amount").contains("cardNumber").contains("cvv");
                });
        // nothing was persisted for the invalid attempts
        assertThat(paymentRepository.findByBookingReferenceOrderByCreatedAt("ABC123")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void publish(String eventId, String type, Map<String, Object> payload) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, type, OffsetDateTime.now(), "booking-service", objectMapper.valueToTree(payload));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
    }

    private List<EventEnvelope> awaitEvents(Predicate<EventEnvelope> filter, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(RECEIVED.stream().filter(filter).count()).isGreaterThanOrEqualTo(expected));
        return RECEIVED.stream().filter(filter).toList();
    }
}
