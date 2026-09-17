package dk.airport.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.booking.messaging.EventEnvelope;
import dk.airport.booking.messaging.ProcessedEventRepository;
import dk.airport.booking.query.BookingOverview;
import dk.airport.booking.query.BookingOverviewRepository;
import dk.airport.booking.query.OverviewBaggage;
import dk.airport.booking.service.FlightClient;
import dk.airport.booking.service.FlightSeatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The CQRS read model booking_overview (dev plan DP-28) against real PostgreSQL + RabbitMQ: the booking's own changes
 * are visible in the overview as soon as the mutation returns, payment.* and baggage.* events of the other services
 * are projected into it, late or repeated events never overwrite newer state, and the query side needs a login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import(TestTokens.class)
@Testcontainers
class BookingOverviewIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-12-24T14:00:00Z");

    static final String CREATE = """
            mutation($flightId: ID!, $seat: String!, $first: String!, $last: String!, $email: String!, $pass: String!) {
              createBooking(flightId: $flightId, seatNumber: $seat, passenger: {
                firstName: $first, lastName: $last, email: $email, passportNumber: $pass }) { bookingReference }
            }""";

    static final String OVERVIEW = """
            query($ref: String!) {
              bookingOverview(reference: $ref) {
                bookingReference status flightNumber gate flightStatus seatNumber price currency projectedAt
                passenger { firstName lastName email passportNumber dateOfBirth }
                payments { paymentId status amount currency cardLast4 failureReason createdAt updatedAt }
                baggage { tagNumber type weightKg status lastLocation registeredAt updatedAt }
              }
            }""";

    @Autowired HttpGraphQlTester graphQlTester;
    GraphQlTester asPassenger;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired BookingOverviewRepository overviews;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FlightClient flightClient;

    @BeforeEach
    void setUp() {
        asPassenger = graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build();
        when(flightClient.fetchFlightSeat(anyLong(), any())).thenAnswer(inv -> new FlightSeatInfo(
                inv.getArgument(0), "SK1501", DEPARTURE, "A12", "SCHEDULED", "DKK",
                inv.getArgument(1), "ECONOMY", true, new BigDecimal("899.00")));
    }

    @Test
    void bookingChangesAreInTheOverviewAsSoonAsTheMutationReturns() {
        String ref = createBooking(asPassenger, 101, "Erik", "Eriksen", "erik@example.com", "P1010101");

        // no waiting: the overview row was written in the createBooking transaction
        asPassenger.document(OVERVIEW).variable("ref", ref.toLowerCase()).execute()
                .path("bookingOverview.status").entity(String.class).isEqualTo("PENDING_PAYMENT")
                .path("bookingOverview.flightNumber").entity(String.class).isEqualTo("SK1501")
                .path("bookingOverview.gate").entity(String.class).isEqualTo("A12")
                .path("bookingOverview.seatNumber").entity(String.class).isEqualTo("1A")
                .path("bookingOverview.price").entity(Double.class).isEqualTo(899.00)
                .path("bookingOverview.passenger.firstName").entity(String.class).isEqualTo("Erik")
                .path("bookingOverview.passenger.email").entity(String.class).isEqualTo("erik@example.com")
                .path("bookingOverview.passenger.passportNumber").entity(String.class).isEqualTo("P1010101")
                .path("bookingOverview.payments").entityList(Object.class).hasSize(0)
                .path("bookingOverview.baggage").entityList(Object.class).hasSize(0);

        asPassenger.document("mutation($ref: String!) { cancelBooking(reference: $ref) { status } }")
                .variable("ref", ref).execute()
                .path("cancelBooking.status").entity(String.class).isEqualTo("CANCELLED");
        assertThat(overview(ref).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void paymentAndBaggageEventsAreProjectedIntoTheOverview() {
        String ref = createBooking(asPassenger, 102, "Gitte", "Gram", "gitte@example.com", "P2020202");

        // payment.completed: payment line + booking CONFIRMED (the write model's reaction, copied in the same tx)
        publish(UUID.randomUUID().toString(), "payment.completed", now(), Map.of("paymentId", 501,
                "bookingReference", ref, "amount", 899.00, "currency", "DKK", "cardLast4", "4242"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            BookingOverview o = overview(ref);
            assertThat(o.getStatus()).isEqualTo("CONFIRMED");
            assertThat(o.getPayments()).singleElement().satisfies(p -> {
                assertThat(p.paymentId()).isEqualTo(501L);
                assertThat(p.status()).isEqualTo("COMPLETED");
                assertThat(p.cardLast4()).isEqualTo("4242");
            });
        });

        // baggage.registered + baggage.status.changed (the second one delivered twice)
        publish(UUID.randomUUID().toString(), "baggage.registered", now(), Map.of("tagNumber", "BAG-GITTE001",
                "bookingReference", ref, "passengerName", "Gitte Gram", "flightNumber", "SK1501", "weightKg", 23.0,
                "type", "CHECKED", "status", "REGISTERED", "lastLocation", "CHECK_IN"));
        String loadedId = UUID.randomUUID().toString();
        Map<String, Object> loaded = Map.of("tagNumber", "BAG-GITTE001", "bookingReference", ref,
                "flightNumber", "SK1501", "oldStatus", "REGISTERED", "newStatus", "LOADED", "location", "Belt 4");
        OffsetDateTime loadedAt = now().plusSeconds(1);
        publish(loadedId, "baggage.status.changed", loadedAt, loaded);
        publish(loadedId, "baggage.status.changed", loadedAt, loaded);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(processedEvents.existsById(loadedId)).isTrue());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(overview(ref).getBaggage()).singleElement().satisfies(b -> {
                    assertThat(b.status()).isEqualTo("LOADED");
                    assertThat(b.lastLocation()).isEqualTo("Belt 4");
                    assertThat(b.type()).isEqualTo("CHECKED");
                    assertThat(b.weightKg()).isEqualByComparingTo("23.0");
                }));

        // check-in is the write model again - visible immediately
        asPassenger.document("mutation($ref: String!) { checkIn(reference: $ref) { status } }")
                .variable("ref", ref).execute()
                .path("checkIn.status").entity(String.class).isEqualTo("CHECKED_IN");
        asPassenger.document(OVERVIEW).variable("ref", ref).execute()
                .path("bookingOverview.status").entity(String.class).isEqualTo("CHECKED_IN")
                .path("bookingOverview.payments[0].status").entity(String.class).isEqualTo("COMPLETED")
                .path("bookingOverview.baggage[0].tagNumber").entity(String.class).isEqualTo("BAG-GITTE001")
                .path("bookingOverview.baggage[0].weightKg").entity(Double.class).isEqualTo(23.0);

        // payment.refunded (after a cancellation) updates the same payment line and keeps the card digits
        publish(UUID.randomUUID().toString(), "payment.refunded", now().plusSeconds(2), Map.of("paymentId", 501,
                "bookingReference", ref, "amount", 899.00, "currency", "DKK"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(overview(ref).getPayments()).singleElement().satisfies(p -> {
                    assertThat(p.status()).isEqualTo("REFUNDED");
                    assertThat(p.cardLast4()).isEqualTo("4242");
                }));

        // the JSON columns hold ISO-8601 timestamps (Spring's ObjectMapper, see JpaJsonConfig), readable in psql
        String json = jdbc.queryForObject("select payments::text from booking_overview where booking_reference = ?",
                String.class, ref);
        assertThat(json).containsPattern("\"createdAt\": ?\"20\\d\\d-\\d\\d-\\d\\dT");
    }

    @Test
    void eventsArrivingOutOfOrderGiveTheSameOverview() {
        String ref = createBooking(asPassenger, 103, "Hans", "Holm", "hans@example.com", "P3030303");
        OffsetDateTime t0 = now();

        // newer events first, then the older ones they would normally follow
        publish(UUID.randomUUID().toString(), "baggage.status.changed", t0.plusSeconds(10), Map.of(
                "tagNumber", "BAG-HANS0001", "bookingReference", ref, "flightNumber", "SK1501",
                "oldStatus", "REGISTERED", "newStatus", "LOADED", "location", "Belt 7"));
        publish(UUID.randomUUID().toString(), "payment.refunded", t0.plusSeconds(10), Map.of("paymentId", 601,
                "bookingReference", ref, "amount", 899.00, "currency", "DKK"));
        publish(UUID.randomUUID().toString(), "baggage.registered", t0, Map.of("tagNumber", "BAG-HANS0001",
                "bookingReference", ref, "passengerName", "Hans Holm", "flightNumber", "SK1501", "weightKg", 20.5,
                "type", "CHECKED", "status", "REGISTERED", "lastLocation", "CHECK_IN"));
        publish(UUID.randomUUID().toString(), "payment.completed", t0, Map.of("paymentId", 601,
                "bookingReference", ref, "amount", 899.00, "currency", "DKK", "cardLast4", "1881"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            BookingOverview o = overview(ref);
            assertThat(o.getPayments()).singleElement().satisfies(p -> {
                assertThat(p.status()).isEqualTo("REFUNDED");               // not reopened by the older event
                assertThat(p.cardLast4()).isEqualTo("1881");                // but filled in from it
                assertThat(p.createdAt().toInstant()).isEqualTo(t0.toInstant());
            });
            assertThat(o.getBaggage()).hasSize(1);
            OverviewBaggage bag = o.getBaggage().get(0);
            assertThat(bag.status()).isEqualTo("LOADED");
            assertThat(bag.lastLocation()).isEqualTo("Belt 7");
            assertThat(bag.weightKg()).isEqualByComparingTo("20.5");
            assertThat(bag.registeredAt().toInstant()).isEqualTo(t0.toInstant());
        });
    }

    @Test
    void eventsForUnknownBookingsAreAcknowledgedAndIgnored() {
        String paymentEvent = UUID.randomUUID().toString();
        String baggageEvent = UUID.randomUUID().toString();
        publish(paymentEvent, "payment.completed", now(), Map.of("paymentId", 701, "bookingReference", "QQQQQQ",
                "amount", 10.00, "currency", "DKK", "cardLast4", "4242"));
        publish(baggageEvent, "baggage.registered", now(), Map.of("tagNumber", "BAG-NOBODY01",
                "bookingReference", "QQQQQQ", "weightKg", 5.0, "type", "CABIN", "status", "REGISTERED"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(processedEvents.existsById(paymentEvent)).isTrue();
            assertThat(processedEvents.existsById(baggageEvent)).isTrue();
        });
        assertThat(overviews.findByBookingReference("QQQQQQ")).isEmpty();
    }

    @Test
    void flightEventsReachTheOverviewThroughTheWriteModel() {
        String ref = createBooking(asPassenger, 104, "Ida", "Isaksen", "ida@example.com", "P4040404");

        publish(UUID.randomUUID().toString(), "flight.gate.changed", now(), Map.of("flightId", 104,
                "flightNumber", "SK1501", "oldGate", "A12", "newGate", "B7"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(overview(ref).getGate()).isEqualTo("B7"));

        publish(UUID.randomUUID().toString(), "flight.cancelled", now(), Map.of("flightId", 104,
                "flightNumber", "SK1501", "reason", "Flight cancelled by airline"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            BookingOverview o = overview(ref);
            assertThat(o.getStatus()).isEqualTo("CANCELLED");
            assertThat(o.getFlightStatus()).isEqualTo("CANCELLED");
        });
    }

    @Test
    void myBookingsListsTheTokensBookingsWithTheLatestPassengerDetails() {
        GraphQlTester asFrida = graphQlTester.mutate()
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("frida", "Frida@Example.com", "PASSENGER"))
                .build();
        String first = createBooking(asFrida, 105, "Frida", "Fisker", "frida@example.com", "P5050505");
        String second = createBooking(asFrida, 106, "Frida", "Fiskersen", "FRIDA@example.com", "P5151515");

        asFrida.document("query { myBookings { bookingReference passenger { lastName passportNumber } } }")
                .execute()
                .path("myBookings[*].bookingReference").entityList(String.class).containsExactly(second, first)
                .path("myBookings[*].passenger.lastName").entityList(String.class)
                .containsExactly("Fiskersen", "Fiskersen")
                .path("myBookings[*].passenger.passportNumber").entityList(String.class)
                .containsExactly("P5151515", "P5151515");

        asPassenger.document("query { myBookings { bookingReference } }")
                .execute()
                .path("myBookings[*].bookingReference").entityList(String.class).doesNotContain(first, second);
    }

    @Test
    void theQuerySideRequiresLogin() {
        String ref = createBooking(asPassenger, 107, "Jens", "Juul", "jens@example.com", "P6060606");

        graphQlTester.document(OVERVIEW).variable("ref", ref).execute()
                .errors().satisfy(errors ->
                        assertThat(errors.get(0).getExtensions()).containsEntry("code", "UNAUTHORIZED"));
        graphQlTester.document("query { myBookings { bookingReference } }").execute()
                .errors().satisfy(errors ->
                        assertThat(errors.get(0).getExtensions()).containsEntry("code", "UNAUTHORIZED"));
        graphQlTester.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build()
                .document(OVERVIEW).variable("ref", ref).execute()
                .path("bookingOverview.bookingReference").entity(String.class).isEqualTo(ref);
        asPassenger.document(OVERVIEW).variable("ref", "ZZZZZZ").execute()
                .path("bookingOverview").valueIsNull();
    }

    // ------------------------------------------------------------------ helpers

    private static String createBooking(GraphQlTester client, long flightId, String first, String last, String email,
                                        String passport) {
        return client.document(CREATE)
                .variable("flightId", flightId).variable("seat", "1A")
                .variable("first", first).variable("last", last).variable("email", email).variable("pass", passport)
                .execute()
                .path("createBooking.bookingReference").entity(String.class).get();
    }

    private BookingOverview overview(String ref) {
        return overviews.findByBookingReference(ref).orElseThrow();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private void publish(String eventId, String type, OffsetDateTime occurredAt, Map<String, Object> payload) {
        try {
            String producer = type.substring(0, type.indexOf('.')) + "-service";
            EventEnvelope env = new EventEnvelope(eventId, type, occurredAt, producer,
                    objectMapper.valueToTree(new HashMap<>(payload)));
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
