package dk.airport.flight;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.flight.messaging.EventEnvelope;
import dk.airport.flight.messaging.ProcessedEventRepository;
import dk.airport.flight.repository.SeatRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * Flyway migrations + seed, GraphQL queries/mutations, event publishing and idempotent consumption.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@Testcontainers
class FlightServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String TEST_QUEUE = "test.flight-events";

    @Autowired GraphQlTester graphQlTester;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired ObjectMapper objectMapper;
    @Autowired SeatRepository seatRepository;
    @Autowired ProcessedEventRepository processedEventRepository;

    /** Every event published on airport.events with routing key flight.* ends up here. */
    static final List<EventEnvelope> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory, @Autowired ObjectMapper objectMapper) {
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

    @Test
    void seedDataIsLoadedByFlyway() {
        graphQlTester.document("{ airlines { id iataCode name } }")
                .execute()
                .path("airlines").entityList(Object.class).hasSizeGreaterThan(2);

        graphQlTester.document("{ flights { id flightNumber destination status gate availableSeatCount airline { iataCode } } }")
                .execute()
                .path("flights").entityList(Object.class).hasSizeGreaterThan(9);
    }

    @Test
    void filterAndSeatPriceWork() {
        Long flightId = graphQlTester.document("{ flights(filter: { destination: \"lhr\" }) { id basePrice } }")
                .execute()
                .path("flights[0].id").entity(Long.class).get();

        graphQlTester.document("query($id: ID!) { flight(id: $id) { seat(seatNumber: \"1A\") { seatClass price isAvailable } } }")
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
                assertThat(seatRepository.findByFlightIdAndSeatNumberIgnoreCase(flightId, "5C").orElseThrow().isAvailable()).isFalse());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());

        graphQlTester.document("query($id: ID!) { availableSeats(flightId: $id) { seatNumber } }")
                .variable("id", flightId)
                .execute()
                .path("availableSeats[*].seatNumber").entityList(String.class).doesNotContain("5C");

        publish(UUID.randomUUID().toString(), "booking.cancelled", Map.of(
                "bookingReference", "ABC123", "flightId", flightId, "seatNumber", "5C"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(seatRepository.findByFlightIdAndSeatNumberIgnoreCase(flightId, "5C").orElseThrow().isAvailable()).isTrue());
    }

    @Test
    void cancellingFlightPublishesStatusChangedAndCancelledEvents() throws Exception {
        Long flightId = graphQlTester.document("{ flights(filter: { destination: \"HEL\" }) { id } }")
                .execute().path("flights[0].id").entity(Long.class).get();

        graphQlTester.document("mutation($id: ID!) { updateFlightStatus(flightId: $id, status: CANCELLED) { id status } }")
                .variable("id", flightId)
                .execute()
                .path("updateFlightStatus.status").entity(String.class).isEqualTo("CANCELLED");

        List<EventEnvelope> received = awaitEvents(e -> e.payload().path("flightNumber").asText().equals("DY1050"), 2);
        assertThat(received).extracting(EventEnvelope::eventType)
                .containsExactlyInAnyOrder("flight.status.changed", "flight.cancelled");
        EventEnvelope cancelled = received.stream().filter(e -> e.eventType().equals("flight.cancelled")).findFirst().orElseThrow();
        assertThat(cancelled.eventId()).isNotBlank();
        assertThat(cancelled.producer()).isEqualTo("flight-service");
        assertThat(cancelled.occurredAt()).isNotNull();
        assertThat(cancelled.payload().get("flightId").asLong()).isEqualTo(flightId);
        assertThat(cancelled.payload().get("flightNumber").asText()).isEqualTo("DY1050");

        // second cancel is an INVALID_STATE error
        graphQlTester.document("mutation($id: ID!) { updateFlightStatus(flightId: $id, status: CANCELLED) { id } }")
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
        graphQlTester.document("""
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
    }

    @Test
    void validationErrorsAreReportedWithCode() {
        graphQlTester.document("mutation { createAirline(input: { iataCode: \"TOOLONG\", name: \"\", country: \"DK\" }) { id } }")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                    assertThat(errors.get(0).getMessage()).contains("iataCode").contains("name");
                });
    }

    @Test
    void unknownFlightGivesNotFound() {
        graphQlTester.document("mutation { updateGate(flightId: 999999, gate: \"Z9\") { id } }")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND");
                });
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
