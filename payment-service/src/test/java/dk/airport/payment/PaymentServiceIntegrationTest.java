package dk.airport.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.payment.messaging.EventEnvelope;
import dk.airport.payment.messaging.EventPublisher;
import dk.airport.payment.messaging.OutboxEvent;
import dk.airport.payment.messaging.OutboxEventRepository;
import dk.airport.payment.messaging.ProcessedEventRepository;
import dk.airport.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * End-to-end test against real PostgreSQL + RabbitMQ (Testcontainers):
 * Flyway migrations, GraphQL pay/refund, event publishing and idempotent consumption of booking.cancelled.
 * Requests go over HTTP through the security filter chain; mutations carry a test token from {@link TestTokens}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import(TestTokens.class)
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

    /** Anonymous client - enough for public queries. */
    @Autowired HttpGraphQlTester graphQlTester;
    /** Same client with a PASSENGER (anna) / OPERATIONS (ops) token. */
    GraphQlTester asPassenger;
    GraphQlTester asOperations;
    /** Spy so a single test can make the outbox relay's publish attempt fail (reset after every test). */
    @MockitoSpyBean RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired EventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory,
                                  @Autowired ObjectMapper objectMapper) {
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

    @BeforeEach
    void authenticatedClients() {
        asPassenger = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build();
        asOperations = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build();
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
        GraphQlTester.Response response = asPassenger.document(PAY)
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
        asPassenger.document(PAY)
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
        asPassenger.document(PAY)
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
        publish(UUID.randomUUID().toString(), "booking.cancelled",
                Map.of("bookingReference", "NOPAY1", "status", "CANCELLED"));
        publish(UUID.randomUUID().toString(), "booking.confirmed",
                Map.of("bookingReference", REF_FAIL, "status", "CONFIRMED"));
        Thread.sleep(1000);
        assertThat(paymentRepository.findByBookingReferenceOrderByCreatedAt(REF_FAIL))
                .extracting(p -> p.getStatus().name()).containsExactly("FAILED");
    }

    @Test
    @Order(5)
    void paymentsByBookingListsHistory() {
        asPassenger.document("query($ref: String!) { paymentsByBooking(reference: $ref) { id status amount } }")
                .variable("ref", REF_OK)
                .execute()
                .path("paymentsByBooking").entityList(Object.class).hasSize(1)
                .path("paymentsByBooking[0].status").entity(String.class).isEqualTo("REFUNDED");

        asPassenger.document("query($ref: String!) { paymentsByBooking(reference: $ref) { id status } }")
                .variable("ref", "ZZZZZZ")
                .execute()
                .path("paymentsByBooking").entityList(Object.class).hasSize(0);
    }

    @Test
    @Order(6)
    void refundOfFailedPaymentIsInvalidState() {
        Long failedId = paymentRepository.findByBookingReferenceOrderByCreatedAt(REF_FAIL).get(0).getId();
        asOperations.document("mutation($id: ID!) { refund(paymentId: $id) { id status } }")
                .variable("id", failedId)
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "INVALID_STATE");
                });

        asOperations.document("mutation { refund(paymentId: 999999) { id } }")
                .execute()
                .errors().satisfy(errors ->
                        assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND"));
    }

    @Test
    @Order(7)
    void manualRefundOfCompletedPaymentWorks() {
        Long id = asPassenger.document(PAY)
                .variable("ref", "REFUND").variable("amount", 100.00)
                .variable("card", "5555555555554444").variable("expiry", "01/2031").variable("cvv", "0000")
                .execute()
                .path("pay.status").entity(String.class).isEqualTo("COMPLETED")
                .path("pay.id").entity(Long.class).get();

        asOperations.document("mutation($id: ID!) { refund(paymentId: $id) { id status } }")
                .variable("id", id)
                .execute()
                .path("refund.status").entity(String.class).isEqualTo("REFUNDED");

        awaitEvents(e -> e.eventType().equals("payment.refunded") && e.payload().path("paymentId").asLong() == id, 1);
    }

    /**
     * Saga compensation (dev plan DP-32): a payment that reaches booking-service after the booking was cancelled is
     * refunded via booking.payment.rejected. Whatever combination of booking.cancelled (also repeated) and
     * booking.payment.rejected arrives, the payment is refunded exactly once.
     */
    @Test
    @Order(8)
    void lateAndRepeatedCancellationEventsRefundAPaymentExactlyOnce() throws Exception {
        Long late = payFor("LATE01");
        publish(UUID.randomUUID().toString(), "booking.payment.rejected", Map.of("bookingReference", "LATE01",
                "paymentId", late, "status", "CANCELLED", "reason", "Payment arrived after the booking was cancelled"));
        awaitEvents(e -> e.eventType().equals("payment.refunded") && e.payload().path("paymentId").asLong() == late, 1);
        publish(UUID.randomUUID().toString(), "booking.payment.rejected", Map.of("bookingReference", "LATE01",
                "paymentId", late, "status", "CANCELLED"));
        publish(UUID.randomUUID().toString(), "booking.cancelled", Map.of("bookingReference", "LATE01",
                "status", "CANCELLED", "reason", "Payment not received in time"));

        // the other order: booking.cancelled refunds first, the rejection arrives afterwards (twice)
        Long early = payFor("LATE02");
        publish(UUID.randomUUID().toString(), "booking.cancelled", Map.of("bookingReference", "LATE02",
                "status", "CANCELLED", "reason", "Payment not received in time"));
        awaitEvents(e -> e.eventType().equals("payment.refunded") && e.payload().path("paymentId").asLong() == early,
                1);
        publish(UUID.randomUUID().toString(), "booking.cancelled", Map.of("bookingReference", "LATE02",
                "status", "CANCELLED"));
        publish(UUID.randomUUID().toString(), "booking.payment.rejected", Map.of("bookingReference", "LATE02",
                "paymentId", early, "status", "CANCELLED"));
        // a rejection whose payment belongs to another booking refunds nothing
        Long other = payFor("LATE03");
        publish(UUID.randomUUID().toString(), "booking.payment.rejected", Map.of("bookingReference", "OTHER1",
                "paymentId", other, "status", "CANCELLED"));

        Thread.sleep(2000);   // let the repeated events be consumed, then count
        assertThat(paymentRepository.findById(other).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        for (Long id : List.of(late, early)) {
            assertThat(RECEIVED.stream().filter(e -> e.eventType().equals("payment.refunded")
                    && e.payload().path("paymentId").asLong() == id).count()).as("refunds of payment %d", id)
                    .isEqualTo(1);
            assertThat(paymentRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("REFUNDED");
        }
    }

    private Long payFor(String reference) {
        return asPassenger.document(PAY)
                .variable("ref", reference).variable("amount", 499.00)
                .variable("card", "4242424242424242").variable("expiry", "12/30").variable("cvv", "123")
                .execute()
                .path("pay.status").entity(String.class).isEqualTo("COMPLETED")
                .path("pay.id").entity(Long.class).get();
    }

    /**
     * Dev plan DP-30: eight simultaneous pay calls for one booking. The ALREADY_PAID check is a read before the
     * insert, so on its own it could let several of them through; the partial unique index ux_payment_one_completed
     * guarantees exactly one COMPLETED payment and one payment.completed event, and every other caller gets
     * ALREADY_PAID - whichever of the two guards stopped it.
     */
    @Test
    @Order(9)
    void simultaneousPaymentsForOneBookingCompleteOnlyOnce() throws Exception {
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<GraphQlResponse>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return asPassenger.document(PAY)
                        .variable("ref", "RACE01").variable("amount", 899.00)
                        .variable("card", "4242424242424242").variable("expiry", "12/30").variable("cvv", "123")
                        .execute().returnResponse();
            }));
        }
        start.countDown();
        int completed = 0;
        for (Future<GraphQlResponse> call : calls) {
            GraphQlResponse response = call.get(60, TimeUnit.SECONDS);
            if (response.getErrors().isEmpty()) {
                assertThat(response.field("pay.status").<String>getValue()).isEqualTo("COMPLETED");
                completed++;
            } else {
                assertThat(response.getErrors().get(0).getExtensions()).containsEntry("code", "ALREADY_PAID");
            }
        }
        pool.shutdown();

        assertThat(completed).isEqualTo(1);
        assertThat(paymentRepository.findByBookingReferenceOrderByCreatedAt("RACE01")).singleElement()
                .satisfies(p -> assertThat(p.getStatus().name()).isEqualTo("COMPLETED"));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> RECEIVED.stream()
                .filter(e -> e.eventType().equals("payment.completed")
                        && e.payload().path("bookingReference").asText().equals("RACE01")).count() == 1);

        // the database guard on its own, independent of timing: a second COMPLETED row is refused
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into payment (booking_reference, amount, card_last4, status)"
                        + " values ('RACE01', 899.00, '4242', 'COMPLETED')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_payment_one_completed");
    }

    @Test
    @Order(10)
    void invalidInputIsValidationError() {
        asPassenger.document(PAY)
                .variable("ref", "ABC123").variable("amount", 10.00)
                .variable("card", "4242424242424242").variable("expiry", "2030-12").variable("cvv", "123")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                    assertThat(errors.get(0).getMessage()).contains("expiry");
                });

        asPassenger.document(PAY)
                .variable("ref", "TOOLONGREF").variable("amount", -1)
                .variable("card", "1234").variable("expiry", "12/30").variable("cvv", "12")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                    assertThat(errors.get(0).getMessage())
                            .contains("bookingReference").contains("amount").contains("cardNumber").contains("cvv");
                });
        // nothing was persisted for the invalid attempts
        assertThat(paymentRepository.findByBookingReferenceOrderByCreatedAt("ABC123")).isEmpty();
    }

    // ------------------------------------------------------------------ outbox guarantees

    @Test
    void publishOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> eventPublisher.publish("payment.test", Map.of("marker", "NOTX1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThat(RECEIVED.stream().filter(e -> e.payload().path("marker").asText().equals("NOTX1"))).isEmpty();
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRowAndNoEvent() {
        String eventId = transactionTemplate.execute(status -> {
            EventEnvelope env = eventPublisher.publish("payment.test", Map.of("marker", "RLBK1"));
            assertThat(outboxEventRepository.findByEventId(env.eventId())).isPresent();   // visible inside the tx
            status.setRollbackOnly();
            return env.eventId();
        });

        assertThat(outboxEventRepository.findByEventId(eventId)).isEmpty();
        // nothing may show up on the broker either - wait longer than a few poll intervals to be sure
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> RECEIVED.stream().noneMatch(e -> e.eventId().equals(eventId)));
    }

    @Test
    void relayRetriesUntilBrokerConfirms() {
        // start from a quiet outbox so the first failing invoke() below is guaranteed to hit our row
        await().atMost(Duration.ofSeconds(10)).until(() -> outboxEventRepository.countByPublishedAtIsNull() == 0);
        doThrow(new AmqpException("simulated broker failure")).doCallRealMethod()
                .when(rabbitTemplate).invoke(any(), any(), any());

        String eventId = transactionTemplate.execute(status ->
                eventPublisher.publish("payment.test", Map.of("marker", "RTRY1")).eventId());

        // first poll fails and is recorded, the next poll succeeds and the event arrives exactly once
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent row = outboxEventRepository.findByEventId(eventId).orElseThrow();
            assertThat(row.getPublishedAt()).isNotNull();
            assertThat(row.getAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(row.getLastError()).contains("simulated broker failure");
        });
        List<EventEnvelope> received = awaitEvents(e -> e.eventId().equals(eventId), 1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).eventType()).isEqualTo("payment.test");
        assertThat(meterRegistry.get("outbox.pending").gauge().value()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private void publish(String eventId, String type, Map<String, Object> payload) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, type, OffsetDateTime.now(), "booking-service",
                objectMapper.valueToTree(payload));
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
