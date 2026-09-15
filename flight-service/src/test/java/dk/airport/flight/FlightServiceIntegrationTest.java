package dk.airport.flight;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.flight.messaging.EventEnvelope;
import dk.airport.flight.messaging.EventPublisher;
import dk.airport.flight.messaging.OutboxEvent;
import dk.airport.flight.messaging.OutboxEventRepository;
import dk.airport.flight.messaging.ProcessedEventRepository;
import dk.airport.flight.repository.SeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * End-to-end test against real PostgreSQL + RabbitMQ (Testcontainers):
 * Flyway migrations + seed, GraphQL queries/mutations, event publishing through the transactional outbox
 * and idempotent consumption. Requests go over HTTP through the security filter chain; mutations are sent with
 * a test token from {@link TestTokens} (queries need none).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import(TestTokens.class)
@Testcontainers
class FlightServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String TEST_QUEUE = "test.flight-events";

    /** Anonymous client - enough for every query. */
    @Autowired HttpGraphQlTester graphQlTester;
    /** Same client with an OPERATIONS token - mutations require that role. */
    GraphQlTester asOperations;
    /** Spy so a single test can make the outbox relay's publish attempt fail (reset after every test). */
    @MockitoSpyBean RabbitTemplate rabbitTemplate;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired ObjectMapper objectMapper;
    @Autowired SeatRepository seatRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired EventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MeterRegistry meterRegistry;

    /** Every event published on airport.events with routing key flight.* ends up here. */
    static final List<EventEnvelope> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory,
                                  @Autowired ObjectMapper objectMapper) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(TEST_QUEUE, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("airport.events")).with("flight.#"));

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
        asOperations = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build();
    }

    @Test
    void seedDataIsLoadedByFlyway() {
        graphQlTester.document("{ airlines { id iataCode name } }")
                .execute()
                .path("airlines").entityList(Object.class).hasSizeGreaterThan(2);

        graphQlTester.document("{ flights { id flightNumber destination status gate availableSeatCount airline "
                        + "{ iataCode } } }")
                .execute()
                .path("flights").entityList(Object.class).hasSizeGreaterThan(9);
    }

    @Test
    void flightByNumberIsCaseInsensitiveAndScopedToTheDate() {
        String departure = graphQlTester.document("{ flights { flightNumber scheduledDeparture } }")
                .execute().path("flights[?(@.flightNumber == 'SK1501')].scheduledDeparture")
                .entityList(String.class).get().getFirst();
        LocalDate date = OffsetDateTime.parse(departure).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();

        String byNumber = "query($n: String!, $d: Date!) "
                + "{ flightByNumber(flightNumber: $n, date: $d) { flightNumber } }";
        graphQlTester.document(byNumber)
                .variable("n", "sk1501").variable("d", date)
                .execute()
                .path("flightByNumber.flightNumber").entity(String.class).isEqualTo("SK1501");

        graphQlTester.document(byNumber)
                .variable("n", "SK1501").variable("d", date.minusDays(1))
                .execute()
                .path("flightByNumber").valueIsNull();
    }

    @Test
    void filterAndSeatPriceWork() {
        Long flightId = graphQlTester.document("{ flights(filter: { destination: \"lhr\" }) { id basePrice } }")
                .execute()
                .path("flights[0].id").entity(Long.class).get();

        graphQlTester.document("query($id: ID!) { flight(id: $id) { seat(seatNumber: \"1A\") "
                        + "{ seatClass price isAvailable } } }")
                .variable("id", flightId)
                .execute()
                .path("flight.seat.seatClass").entity(String.class).isEqualTo("BUSINESS")
                .path("flight.seat.price").entity(Double.class).isEqualTo(2247.50)
                .path("flight.seat.isAvailable").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void bookingConfirmedMarksSeatTakenAndIsIdempotent() throws Exception {
        Long flightId = graphQlTester.document("{ flights(filter: { destination: \"ARN\" }) { id } }")
                .execute().path("flights[0].id").entity(Long.class).get();

        String eventId = UUID.randomUUID().toString();
        publish(eventId, "booking.confirmed", Map.of(
                "bookingReference", "ABC123", "flightId", flightId, "seatNumber", "5C"));
        // duplicate delivery of the same event must be ignored
        publish(eventId, "booking.confirmed", Map.of(
                "bookingReference", "ABC123", "flightId", flightId, "seatNumber", "5C"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(seatRepository.findByFlightIdAndSeatNumberIgnoreCase(flightId, "5C").orElseThrow()
                        .isAvailable()).isFalse());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());

        graphQlTester.document("query($id: ID!) { availableSeats(flightId: $id) { seatNumber } }")
                .variable("id", flightId)
                .execute()
                .path("availableSeats[*].seatNumber").entityList(String.class).doesNotContain("5C");

        publish(UUID.randomUUID().toString(), "booking.cancelled", Map.of(
                "bookingReference", "ABC123", "flightId", flightId, "seatNumber", "5C"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(seatRepository.findByFlightIdAndSeatNumberIgnoreCase(flightId, "5C").orElseThrow()
                        .isAvailable()).isTrue());
    }

    @Test
    void cancellingFlightPublishesStatusChangedAndCancelledEvents() throws Exception {
        Long flightId = graphQlTester.document("{ flights(filter: { destination: \"HEL\" }) { id } }")
                .execute().path("flights[0].id").entity(Long.class).get();

        asOperations.document(
                        "mutation($id: ID!) { updateFlightStatus(flightId: $id, status: CANCELLED) { id status } }")
                .variable("id", flightId)
                .execute()
                .path("updateFlightStatus.status").entity(String.class).isEqualTo("CANCELLED");

        List<EventEnvelope> received = awaitEvents(e -> e.payload().path("flightNumber").asText().equals("DY1050"), 2);
        assertThat(received).extracting(EventEnvelope::eventType)
                .containsExactlyInAnyOrder("flight.status.changed", "flight.cancelled");
        EventEnvelope cancelled = received.stream()
                .filter(e -> e.eventType().equals("flight.cancelled")).findFirst().orElseThrow();
        assertThat(cancelled.eventId()).isNotBlank();
        assertThat(cancelled.producer()).isEqualTo("flight-service");
        assertThat(cancelled.occurredAt()).isNotNull();
        assertThat(cancelled.payload().get("flightId").asLong()).isEqualTo(flightId);
        assertThat(cancelled.payload().get("flightNumber").asText()).isEqualTo("DY1050");

        // second cancel is an INVALID_STATE error
        asOperations.document("mutation($id: ID!) { updateFlightStatus(flightId: $id, status: CANCELLED) { id } }")
                .variable("id", flightId)
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "INVALID_STATE");
                });
    }

    @Test
    void createFlightGeneratesSeatsAndPublishesEvent() throws Exception {
        OffsetDateTime dep = OffsetDateTime.now().plusDays(3).withNano(0);
        asOperations.document("""
                mutation($dep: DateTime!, $arr: DateTime!) {
                  createFlight(input: { flightNumber: "SK9999", airlineId: 1, aircraftId: 5, origin: "CPH",
                                        destination: "AAL", scheduledDeparture: $dep, scheduledArrival: $arr,
                                        gate: "A1", basePrice: 399.00 }) {
                    id flightNumber status availableSeatCount seats { seatNumber }
                  }
                }""")
                .variable("dep", dep.toString())
                .variable("arr", dep.plusMinutes(45).toString())
                .execute()
                .path("createFlight.status").entity(String.class).isEqualTo("SCHEDULED")
                .path("createFlight.availableSeatCount").entity(Integer.class).isEqualTo(100)
                .path("createFlight.seats").entityList(Object.class).hasSize(100);

        List<EventEnvelope> received = awaitEvents(e -> e.payload().path("flightNumber").asText().equals("SK9999"), 1);
        assertThat(received.get(0).eventType()).isEqualTo("flight.created");
        assertThat(received.get(0).producer()).isEqualTo("flight-service");

        // the event went through the outbox: same eventId, committed with the flight, marked once confirmed
        OutboxEvent row = outboxEventRepository.findByEventId(received.get(0).eventId()).orElseThrow();
        assertThat(row.getEventType()).isEqualTo("flight.created");
        assertThat(row.getPayload()).contains("SK9999");
        assertThat(row.getAttempts()).isZero();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outboxEventRepository.findByEventId(row.getEventId()).orElseThrow().getPublishedAt())
                        .isNotNull());
    }

    // ------------------------------------------------------------------ outbox guarantees

    @Test
    void publishOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> eventPublisher.publish("flight.test", Map.of("marker", "NOTX1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThat(RECEIVED.stream().filter(e -> e.payload().path("marker").asText().equals("NOTX1"))).isEmpty();
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRowAndNoEvent() {
        String eventId = transactionTemplate.execute(status -> {
            EventEnvelope env = eventPublisher.publish("flight.test", Map.of("marker", "RLBK1"));
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
                eventPublisher.publish("flight.test", Map.of("marker", "RTRY1")).eventId());

        // first poll fails and is recorded, the next poll succeeds and the event arrives exactly once
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent row = outboxEventRepository.findByEventId(eventId).orElseThrow();
            assertThat(row.getPublishedAt()).isNotNull();
            assertThat(row.getAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(row.getLastError()).contains("simulated broker failure");
        });
        List<EventEnvelope> received = awaitEvents(e -> e.eventId().equals(eventId), 1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).eventType()).isEqualTo("flight.test");
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
