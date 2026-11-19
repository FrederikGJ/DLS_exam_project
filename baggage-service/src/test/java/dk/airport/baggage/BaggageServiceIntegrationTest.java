package dk.airport.baggage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.domain.BaggageStatus;
import dk.airport.baggage.domain.BookingSnapshot;
import dk.airport.baggage.messaging.EventEnvelope;
import dk.airport.baggage.messaging.EventPublisher;
import dk.airport.baggage.messaging.OutboxEvent;
import dk.airport.baggage.messaging.OutboxEventRepository;
import dk.airport.baggage.messaging.ProcessedEventRepository;
import dk.airport.baggage.repository.BaggageRepository;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test against real PostgreSQL + RabbitMQ (Testcontainers): Flyway migrations, GraphQL,
 * booking/flight event consumption (idempotent) and publishing of baggage.* events.
 * Steps build on each other, hence the explicit ordering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@AutoConfigureMockMvc
@Import(TestTokens.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BaggageServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String TEST_QUEUE = "test.baggage-events";
    static final String REF = "K7Q2ZP";
    static final String FLIGHT_NUMBER = "SK1501";
    static final long FLIGHT_ID = 1L;

    /** Every event published on airport.events with routing key baggage.# ends up here. */
    static final List<EventEnvelope> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;
    static String firstTag;

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired MockMvc mvc;
    GraphQlTester asPassenger;
    GraphQlTester asOperations;

    @BeforeEach
    void authenticatedClients() {
        asPassenger = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build();
        asOperations = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build();
    }
    /** Spy so a single test can make the outbox relay's publish attempt fail (reset after every test). */
    @MockitoSpyBean RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired BaggageRepository baggageRepository;
    @Autowired BookingSnapshotRepository snapshotRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired EventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MeterRegistry meterRegistry;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory, @Autowired ObjectMapper objectMapper) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(TEST_QUEUE, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("airport.events")).with("baggage.#"));

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

    @Test
    @Order(1)
    void unknownBookingIsNotFound() {
        register(REF, "23.0", "CHECKED").errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND");
        });
    }

    @Test
    @Order(2)
    void pendingBookingCannotRegisterBaggage() throws Exception {
        publish(UUID.randomUUID().toString(), "booking.created", bookingPayload("PENDING_PAYMENT"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(snapshotRepository.findById(REF)).isPresent());

        asPassenger.document("query($ref: String!) { bookingSnapshot(reference: $ref) { bookingReference passengerName flightNumber status } }")
                .variable("ref", REF)
                .execute()
                .path("bookingSnapshot.status").entity(String.class).isEqualTo("PENDING_PAYMENT")
                .path("bookingSnapshot.passengerName").entity(String.class).isEqualTo("Anna Jensen")
                .path("bookingSnapshot.flightNumber").entity(String.class).isEqualTo(FLIGHT_NUMBER);

        register(REF, "23.0", "CHECKED").errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getExtensions()).containsEntry("code", "INVALID_STATE");
        });
    }

    @Test
    @Order(3)
    void confirmedBookingEventIsIdempotentAndEnablesRegistration() throws Exception {
        String eventId = UUID.randomUUID().toString();
        publish(eventId, "booking.confirmed", bookingPayload("CONFIRMED"));
        publish(eventId, "booking.confirmed", bookingPayload("CONFIRMED")); // duplicate delivery

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(snapshotRepository.findById(REF).orElseThrow().getStatus()).isEqualTo("CONFIRMED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());

        firstTag = register(REF, "23.0", "CHECKED")
                .path("registerBaggage.status").entity(String.class).isEqualTo("REGISTERED")
                .path("registerBaggage.passengerName").entity(String.class).isEqualTo("Anna Jensen")
                .path("registerBaggage.flightNumber").entity(String.class).isEqualTo(FLIGHT_NUMBER)
                .path("registerBaggage.lastLocation").entity(String.class).isEqualTo("CHECK_IN")
                .path("registerBaggage.weightKg").entity(Double.class).isEqualTo(23.0)
                .path("registerBaggage.tagNumber").entity(String.class).matches(t -> t.matches("^BAG-[A-Z0-9]{8}$")).get();

        List<EventEnvelope> events = awaitEvents(e -> e.eventType().equals("baggage.registered")
                && e.payload().path("tagNumber").asText().equals(firstTag), 1);
        EventEnvelope registered = events.get(0);
        assertThat(registered.producer()).isEqualTo("baggage-service");
        assertThat(registered.eventId()).isNotBlank();
        assertThat(registered.occurredAt()).isNotNull();
        assertThat(registered.payload().path("bookingReference").asText()).isEqualTo(REF);
        assertThat(registered.payload().path("type").asText()).isEqualTo("CHECKED");
        assertThat(registered.payload().path("status").asText()).isEqualTo("REGISTERED");
    }

    @Test
    @Order(4)
    void overweightBagIsRejected() {
        register(REF, "33.0", "CHECKED").errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
        });
    }

    @Test
    @Order(5)
    void maxThreeCheckedBagsPerBooking() {
        // one CHECKED bag already exists (step 3); two more are fine
        register(REF, "18.5", "CHECKED").path("registerBaggage.tagNumber").entity(String.class).matches(t -> t.startsWith("BAG-"));
        register(REF, "32.0", "CHECKED").path("registerBaggage.tagNumber").entity(String.class).matches(t -> t.startsWith("BAG-"));

        register(REF, "10.0", "CHECKED").errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getExtensions()).containsEntry("code", "BAGGAGE_LIMIT_EXCEEDED");
        });

        // the checked limit does not apply to CABIN bags
        register(REF, "7.0", "CABIN").path("registerBaggage.type").entity(String.class).isEqualTo("CABIN");

        assertThat(baggageRepository.findByBookingReferenceIgnoreCaseOrderByCreatedAt(REF)).hasSize(4);
    }

    @Test
    @Order(6)
    void updateStatusPublishesStatusChanged() {
        asOperations.document("mutation($tag: String!) { updateBaggageStatus(tagNumber: $tag, status: LOADED, location: \"Belt 4\") { tagNumber status lastLocation } }")
                .variable("tag", firstTag)
                .execute()
                .path("updateBaggageStatus.status").entity(String.class).isEqualTo("LOADED")
                .path("updateBaggageStatus.lastLocation").entity(String.class).isEqualTo("Belt 4");

        List<EventEnvelope> events = awaitEvents(e -> e.eventType().equals("baggage.status.changed")
                && e.payload().path("tagNumber").asText().equals(firstTag)
                && e.payload().path("newStatus").asText().equals("LOADED"), 1);
        assertThat(events.get(0).payload().path("oldStatus").asText()).isEqualTo("REGISTERED");
        assertThat(events.get(0).payload().path("location").asText()).isEqualTo("Belt 4");
        assertThat(events.get(0).payload().path("bookingReference").asText()).isEqualTo(REF);

        asOperations.document("mutation { updateBaggageStatus(tagNumber: \"BAG-NOPE0000\", status: LOST) { tagNumber } }")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND");
                });
    }

    @Test
    @Order(7)
    void flightCancelledSendsBaggageToReturnDesk() throws Exception {
        publish(UUID.randomUUID().toString(), "flight.cancelled", Map.of(
                "flightId", FLIGHT_ID, "flightNumber", FLIGHT_NUMBER,
                "scheduledDeparture", "2026-09-11T14:00:00Z", "reason", "Flight cancelled by airline"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<Baggage> bags = baggageRepository.findByFlightNumberIgnoreCaseOrderByCreatedAt(FLIGHT_NUMBER);
            assertThat(bags).hasSize(4);
            assertThat(bags).allMatch(b -> b.getStatus() == BaggageStatus.REGISTERED && "RETURN_DESK".equals(b.getLastLocation()));
        });
        assertThat(snapshotRepository.findById(REF).orElseThrow().getStatus()).isEqualTo("CANCELLED");

        // the LOADED bag was moved back: a status.changed event with location RETURN_DESK
        awaitEvents(e -> e.eventType().equals("baggage.status.changed")
                && e.payload().path("tagNumber").asText().equals(firstTag)
                && e.payload().path("location").asText().equals("RETURN_DESK"), 1);
    }

    @Test
    @Order(8)
    void queriesReturnBaggage() {
        asPassenger.document("query($ref: String!) { baggageByBooking(reference: $ref) { tagNumber status lastLocation type weightKg } }")
                .variable("ref", REF)
                .execute()
                .path("baggageByBooking").entityList(Object.class).hasSize(4)
                .path("baggageByBooking[*].lastLocation").entityList(String.class).containsExactly("RETURN_DESK", "RETURN_DESK", "RETURN_DESK", "RETURN_DESK");

        asOperations.document("query($fn: String!) { baggageByFlight(flightNumber: $fn) { tagNumber } }")
                .variable("fn", FLIGHT_NUMBER)
                .execute()
                .path("baggageByFlight").entityList(Object.class).hasSize(4);

        asPassenger.document("query($tag: String!) { baggage(tagNumber: $tag) { tagNumber bookingReference passengerName status updatedAt } }")
                .variable("tag", firstTag)
                .execute()
                .path("baggage.bookingReference").entity(String.class).isEqualTo(REF)
                .path("baggage.status").entity(String.class).isEqualTo("REGISTERED");

        asPassenger.document("{ baggage(tagNumber: \"BAG-UNKNOWN1\") { tagNumber } }")
                .execute()
                .path("baggage").valueIsNull();
    }

    // ------------------------------------------------------------------ outbox guarantees

    @Test
    void restRegistrationListAndStatusShareDataWithGraphQl() throws Exception {
        String ref = "REST01";
        snapshotRepository.save(new BookingSnapshot(ref, "Anna Jensen", "DY1234", 2L, "CONFIRMED"));
        String body = """
                {"bookingReference":"REST01","weightKg":23,"type":"CHECKED"}
                """;
        String registration = mvc.perform(post("/api/baggage/v1/baggage")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andReturn().getResponse().getContentAsString();
        String tag = objectMapper.readTree(registration).path("tagNumber").asText();

        mvc.perform(get("/api/baggage/v1/bookings/{ref}/baggage", ref)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tagNumber").value(tag));

        mvc.perform(patch("/api/baggage/v1/baggage/{tag}/status", tag)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.operations())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"LOADED","location":"Belt 4"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOADED"))
                .andExpect(jsonPath("$.lastLocation").value("Belt 4"));

        asPassenger.document("query($tag: String!) { baggage(tagNumber: $tag) { status } }")
                .variable("tag", tag).execute()
                .path("baggage.status").entity(String.class).isEqualTo("LOADED");
    }

    @Test
    void restErrorsExposeGraphQlCodes() throws Exception {
        String registration = "/api/baggage/v1/baggage";
        String statusPath = registration + "/{tag}/status";
        mvc.perform(get("/api/baggage/v1/bookings/REST02/baggage"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/baggage/v1/bookings/REST02/baggage")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(patch(statusPath, "BAG-UNKNOWN")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOADED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post(registration)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingReference":"REST02","weightKg":32.01,"type":"CHECKED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(registration)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingReference":"REST02","weightKg":23,"type":"CHECKED"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(patch(statusPath, "BAG-UNKNOWN")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.operations())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"unknown\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(patch(statusPath, "BAG-UNKNOWN")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.operations())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOADED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void restAllowsBrowserPatchAndPublishesOpenApiUnderIngressPrefix() throws Exception {
        mvc.perform(options("/api/baggage/v1/baggage/BAG-EXAMPLE/status")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8080")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8080"));

        mvc.perform(get("/api/baggage/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/baggage/v1/bookings/{ref}/baggage'].get").exists())
                .andExpect(jsonPath("$.paths['/api/baggage/v1/baggage'].post").exists())
                .andExpect(jsonPath("$.paths['/api/baggage/v1/baggage/{tag}/status'].patch").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
        mvc.perform(get("/api/baggage/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/api/baggage/swagger-ui/index.html"));
        mvc.perform(get("/api/baggage/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/baggage/swagger-ui/swagger-initializer.js")).andExpect(status().isOk());
        mvc.perform(get("/api/baggage/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/api/baggage/v3/api-docs"));
    }

    @Test
    void publishOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> eventPublisher.publish("baggage.test", Map.of("marker", "NOTX1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThat(RECEIVED.stream().filter(e -> e.payload().path("marker").asText().equals("NOTX1"))).isEmpty();
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRowAndNoEvent() {
        String eventId = transactionTemplate.execute(status -> {
            EventEnvelope env = eventPublisher.publish("baggage.test", Map.of("marker", "RLBK1"));
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
                eventPublisher.publish("baggage.test", Map.of("marker", "RTRY1")).eventId());

        // first poll fails and is recorded, the next poll succeeds and the event arrives exactly once
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent row = outboxEventRepository.findByEventId(eventId).orElseThrow();
            assertThat(row.getPublishedAt()).isNotNull();
            assertThat(row.getAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(row.getLastError()).contains("simulated broker failure");
        });
        List<EventEnvelope> received = awaitEvents(e -> e.eventId().equals(eventId), 1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).eventType()).isEqualTo("baggage.test");
        assertThat(meterRegistry.get("outbox.pending").gauge().value()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private GraphQlTester.Response register(String ref, String weight, String type) {
        return asPassenger.document("mutation($ref: String!, $w: BigDecimal!, $t: BaggageType!) { registerBaggage(bookingReference: $ref, weightKg: $w, type: $t) { id tagNumber bookingReference passengerName flightNumber weightKg type status lastLocation updatedAt } }")
                .variable("ref", ref)
                .variable("w", weight)
                .variable("t", type)
                .execute();
    }

    private static Map<String, Object> bookingPayload(String status) {
        return Map.of(
                "bookingId", 1, "bookingReference", REF,
                "flightId", FLIGHT_ID, "flightNumber", FLIGHT_NUMBER, "departureTime", "2026-09-11T14:00:00Z",
                "seatNumber", "12C", "price", 899.00, "currency", "DKK",
                "status", status,
                "passenger", Map.of("firstName", "Anna", "lastName", "Jensen", "email", "anna@example.com"));
    }

    private void publish(String eventId, String type, Map<String, Object> payload) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, type, OffsetDateTime.now(), "test", objectMapper.valueToTree(payload));
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
