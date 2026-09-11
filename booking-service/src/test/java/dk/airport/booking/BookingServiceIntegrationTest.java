package dk.airport.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.booking.messaging.EventEnvelope;
import dk.airport.booking.messaging.ProcessedEventRepository;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.service.FlightClient;
import dk.airport.booking.service.FlightSeatInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * End-to-end test against real PostgreSQL + RabbitMQ (Testcontainers). flight-service is mocked.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@Testcontainers
class BookingServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String TEST_QUEUE = "test.booking-events";
    static final long FLIGHT_ID = 1L;
    static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-12-24T14:00:00Z");

    /** Every event published on airport.events with routing key booking.# ends up here. */
    static final List<EventEnvelope> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;

    @Autowired GraphQlTester graphQlTester;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired BookingRepository bookingRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @MockitoBean FlightClient flightClient;

    @BeforeAll
    static void startTestListener(@Autowired ConnectionFactory connectionFactory, @Autowired ObjectMapper objectMapper) {
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
    void mockFlightService() {
        when(flightClient.fetchFlightSeat(anyLong(), any())).thenAnswer(inv -> new FlightSeatInfo(
                inv.getArgument(0), "SK1501", DEPARTURE, "A12", "SCHEDULED", "DKK",
                inv.getArgument(1), "ECONOMY", true, new BigDecimal("899.00")));
    }

    @Test
    void fullBookingLifecycle() throws Exception {
        // 1. createBooking -> PENDING_PAYMENT + booking.created
        String reference = graphQlTester.document("""
                mutation {
                  createBooking(flightId: 1, seatNumber: "12C", passenger: {
                    firstName: "Anna", lastName: "Jensen", email: "anna@example.com",
                    passportNumber: "P1234567", dateOfBirth: "1990-05-17" }) {
                    id bookingReference status flightNumber departureTime gate flightStatus seatNumber price currency
                    passenger { firstName lastName email }
                  }
                }""")
                .execute()
                .path("createBooking.status").entity(String.class).isEqualTo("PENDING_PAYMENT")
                .path("createBooking.flightNumber").entity(String.class).isEqualTo("SK1501")
                .path("createBooking.gate").entity(String.class).isEqualTo("A12")
                .path("createBooking.price").entity(Double.class).isEqualTo(899.00)
                .path("createBooking.passenger.email").entity(String.class).isEqualTo("anna@example.com")
                .path("createBooking.bookingReference").entity(String.class).get();
        assertThat(reference).matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$");

        List<EventEnvelope> created = awaitEvents(forRef(reference, "booking.created"), 1);
        assertThat(created.get(0).producer()).isEqualTo("booking-service");
        assertThat(created.get(0).payload().get("flightId").asLong()).isEqualTo(FLIGHT_ID);
        assertThat(created.get(0).payload().get("seatNumber").asText()).isEqualTo("12C");
        assertThat(created.get(0).payload().get("passenger").get("firstName").asText()).isEqualTo("Anna");
        assertThat(created.get(0).payload().get("price").decimalValue()).isEqualByComparingTo("899.00");

        // 2. same seat again -> SEAT_TAKEN
        graphQlTester.document("""
                mutation {
                  createBooking(flightId: 1, seatNumber: "12c", passenger: {
                    firstName: "Bo", lastName: "Hansen", email: "bo@example.com", passportNumber: "P7654321" }) { id }
                }""")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "SEAT_TAKEN");
                });

        // 3. payment.completed (delivered twice with same eventId) -> CONFIRMED + exactly one booking.confirmed
        String eventId = UUID.randomUUID().toString();
        publish(eventId, "payment.completed", "payment-service", Map.of(
                "paymentId", 1, "bookingReference", reference, "amount", 899.00, "currency", "DKK", "cardLast4", "4242"));
        publish(eventId, "payment.completed", "payment-service", Map.of(
                "paymentId", 1, "bookingReference", reference, "amount", 899.00, "currency", "DKK", "cardLast4", "4242"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(bookingRepository.findByBookingReference(reference).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());
        awaitEvents(forRef(reference, "booking.confirmed"), 1);
        // give a potential duplicate a moment to arrive, then assert exactly one
        Thread.sleep(1000);
        assertThat(RECEIVED.stream().filter(forRef(reference, "booking.confirmed")).count()).isEqualTo(1);

        // 4. checkIn -> CHECKED_IN + booking.checkedin
        graphQlTester.document("mutation($ref: String!) { checkIn(reference: $ref) { status } }")
                .variable("ref", reference)
                .execute()
                .path("checkIn.status").entity(String.class).isEqualTo("CHECKED_IN");
        awaitEvents(forRef(reference, "booking.checkedin"), 1);

        // 5. bookingByReference returns snapshot fields (lower case reference is accepted)
        graphQlTester.document("query($ref: String!) { bookingByReference(reference: $ref) { bookingReference status flightId flightNumber departureTime gate flightStatus seatNumber price currency passenger { firstName } } }")
                .variable("ref", reference.toLowerCase())
                .execute()
                .path("bookingByReference.status").entity(String.class).isEqualTo("CHECKED_IN")
                .path("bookingByReference.flightId").entity(Long.class).isEqualTo(FLIGHT_ID)
                .path("bookingByReference.flightNumber").entity(String.class).isEqualTo("SK1501")
                .path("bookingByReference.departureTime").entity(String.class)
                    .satisfies(t -> assertThat(OffsetDateTime.parse(t).toInstant()).isEqualTo(DEPARTURE.toInstant()))
                .path("bookingByReference.gate").entity(String.class).isEqualTo("A12")
                .path("bookingByReference.flightStatus").entity(String.class).isEqualTo("SCHEDULED")
                .path("bookingByReference.seatNumber").entity(String.class).isEqualTo("12C")
                .path("bookingByReference.passenger.firstName").entity(String.class).isEqualTo("Anna");

        // 6. flight.gate.changed -> snapshot updated
        publish(UUID.randomUUID().toString(), "flight.gate.changed", "flight-service", Map.of(
                "flightId", FLIGHT_ID, "flightNumber", "SK1501", "oldGate", "A12", "newGate", "A20"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(bookingRepository.findByBookingReference(reference).orElseThrow().getGate()).isEqualTo("A20"));

        // 7. flight.cancelled -> CANCELLED + booking.cancelled
        publish(UUID.randomUUID().toString(), "flight.cancelled", "flight-service", Map.of(
                "flightId", FLIGHT_ID, "flightNumber", "SK1501", "reason", "Flight cancelled by airline"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(bookingRepository.findByBookingReference(reference).orElseThrow().getStatus().name()).isEqualTo("CANCELLED"));
        List<EventEnvelope> cancelled = awaitEvents(forRef(reference, "booking.cancelled"), 1);
        assertThat(cancelled.get(0).payload().get("reason").asText()).isEqualTo("Flight cancelled");
        assertThat(cancelled.get(0).payload().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(bookingRepository.findByBookingReference(reference).orElseThrow().getFlightStatus()).isEqualTo("CANCELLED");

        // 8. seat is free again after cancellation
        graphQlTester.document("""
                mutation {
                  createBooking(flightId: 1, seatNumber: "12C", passenger: {
                    firstName: "Bo", lastName: "Hansen", email: "bo@example.com", passportNumber: "P7654321" }) { status }
                }""")
                .execute()
                .path("createBooking.status").entity(String.class).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void paymentFailedCancelsPendingBooking() {
        String reference = graphQlTester.document("""
                mutation {
                  createBooking(flightId: 2, seatNumber: "3A", passenger: {
                    firstName: "Carl", lastName: "Nielsen", email: "carl@example.com", passportNumber: "P0000001" }) { bookingReference }
                }""")
                .execute().path("createBooking.bookingReference").entity(String.class).get();

        publish(UUID.randomUUID().toString(), "payment.failed", "payment-service", Map.of(
                "paymentId", 2, "bookingReference", reference, "amount", 899.00, "currency", "DKK",
                "cardLast4", "0000", "failureReason", "Insufficient funds"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(bookingRepository.findByBookingReference(reference).orElseThrow().getStatus().name()).isEqualTo("CANCELLED"));
        List<EventEnvelope> cancelled = awaitEvents(forRef(reference, "booking.cancelled"), 1);
        assertThat(cancelled.get(0).payload().get("reason").asText()).isEqualTo("Payment failed: Insufficient funds");
    }

    @Test
    void cancelByPassengerAndBookingsByPassenger() {
        String reference = graphQlTester.document("""
                mutation {
                  createBooking(flightId: 3, seatNumber: "7F", passenger: {
                    firstName: "Dina", lastName: "Olsen", email: "Dina@Example.com", passportNumber: "P5555555" }) { bookingReference }
                }""")
                .execute().path("createBooking.bookingReference").entity(String.class).get();

        graphQlTester.document("query { bookingsByPassenger(email: \"dina@example.com\") { bookingReference } }")
                .execute()
                .path("bookingsByPassenger[*].bookingReference").entityList(String.class).contains(reference);

        graphQlTester.document("mutation($ref: String!) { cancelBooking(reference: $ref) { status } }")
                .variable("ref", reference)
                .execute()
                .path("cancelBooking.status").entity(String.class).isEqualTo("CANCELLED");
        List<EventEnvelope> cancelled = awaitEvents(forRef(reference, "booking.cancelled"), 1);
        assertThat(cancelled.get(0).payload().get("reason").asText()).isEqualTo("Cancelled by passenger");

        graphQlTester.document("mutation($ref: String!) { cancelBooking(reference: $ref) { status } }")
                .variable("ref", reference)
                .execute()
                .errors().satisfy(errors -> assertThat(errors.get(0).getExtensions()).containsEntry("code", "INVALID_STATE"));
    }

    @Test
    void validationErrorsAreReportedWithCode() {
        graphQlTester.document("""
                mutation {
                  createBooking(flightId: 1, seatNumber: "1A", passenger: {
                    firstName: "", lastName: "X", email: "not-an-email", passportNumber: "ab" }) { id }
                }""")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                    assertThat(errors.get(0).getMessage()).contains("email").contains("firstName").contains("passportNumber");
                });
    }

    @Test
    void unknownReferenceGivesNotFound() {
        graphQlTester.document("mutation { checkIn(reference: \"ZZZZZZ\") { id } }")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND");
                });

        graphQlTester.document("{ bookingByReference(reference: \"ZZZZZZ\") { id } }")
                .execute()
                .path("bookingByReference").valueIsNull();
    }

    // ------------------------------------------------------------------ helpers

    private static Predicate<EventEnvelope> forRef(String reference, String type) {
        return e -> e.eventType().equals(type) && e.payload().path("bookingReference").asText().equals(reference);
    }

    private void publish(String eventId, String type, String producer, Map<String, Object> payload) {
        try {
            EventEnvelope env = new EventEnvelope(eventId, type, OffsetDateTime.now(), producer, objectMapper.valueToTree(payload));
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<EventEnvelope> awaitEvents(Predicate<EventEnvelope> filter, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(RECEIVED.stream().filter(filter).count()).isGreaterThanOrEqualTo(expected));
        return RECEIVED.stream().filter(filter).toList();
    }
}
