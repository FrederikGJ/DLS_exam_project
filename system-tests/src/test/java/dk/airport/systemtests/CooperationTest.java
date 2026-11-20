package dk.airport.systemtests;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.airport.systemtests.EventCollector.Event;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * System-level cooperation test: the real flight-service, booking-service and payment-service images cooperate
 * through RabbitMQ (asynchronous, via each service's transactional outbox) and one synchronous GraphQL call
 * (booking-service asks flight-service for flight, seat and price), observed from the outside only - GraphQL over
 * HTTP in, events on the exchange out. Keycloak is replaced by a WireMock JWKS endpoint serving the public half of
 * the key that signs the test tokens ({@link TestKeys}), so security runs exactly as in production.
 *
 * <p>Every test books on its own seeded flight (different index into the SCHEDULED flights), so the tests do not
 * depend on each other or on their order - the cancellation test cancels "its" flight only.
 */
class CooperationTest {

    private static final String SCHEDULED_FLIGHTS = """
            { flights(filter: { status: SCHEDULED }) { id flightNumber } }""";
    private static final String AVAILABLE_SEATS = """
            query($id: ID!) { availableSeats(flightId: $id) { seatNumber } }""";
    private static final String SEAT_PRICE = """
            query($id: ID!, $seat: String!) { flight(id: $id) { seat(seatNumber: $seat) { price isAvailable } } }""";
    private static final String UPDATE_FLIGHT_STATUS = """
            mutation($id: ID!, $status: FlightStatus!) {
              updateFlightStatus(flightId: $id, status: $status) { status }
            }""";
    private static final String CREATE_BOOKING = """
            mutation($flightId: ID!, $seat: String!, $passenger: PassengerInput!) {
              createBooking(flightId: $flightId, seatNumber: $seat, passenger: $passenger) {
                bookingReference status flightId flightNumber seatNumber price currency
              }
            }""";
    private static final String BOOKING_BY_REFERENCE = """
            query($reference: String!) { bookingByReference(reference: $reference) { status flightStatus } }""";
    private static final String PAY = """
            mutation($reference: String!, $amount: BigDecimal!, $card: String!, $expiry: String!, $cvv: String!) {
              pay(bookingReference: $reference, amount: $amount, cardNumber: $card, expiry: $expiry, cvv: $cvv) {
                id bookingReference amount cardLast4 status failureReason
              }
            }""";
    private static final String PAYMENTS_BY_BOOKING = """
            query($reference: String!) { paymentsByBooking(reference: $reference) { id status } }""";

    private static final Passenger ANNA = new Passenger("Anna", "Andersen", "anna@example.com", "DK123456");
    private static final Passenger BO = new Passenger("Bo", "Hansen", "bo@example.com", "DK654321");
    private static final String VALID_CARD = "4242424242424242";
    /** The simulated gateway declines every card number ending in 0000 ("Insufficient funds"). */
    private static final String DECLINED_CARD = "4000000000000000";
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** A test passenger: the e-mail is also the e-mail claim of their token (a passenger books for themselves). */
    private record Passenger(String firstName, String lastName, String email, String passportNumber) {

        Map<String, Object> input() {
            return Map.of("firstName", firstName, "lastName", lastName, "email", email,
                    "passportNumber", passportNumber);
        }

        String token() {
            return TestKeys.bearer(email.substring(0, email.indexOf('@')), email, "PASSENGER");
        }
    }

    private static Stack stack;
    private static EventCollector events;
    private static GraphQlClient flights;
    private static GraphQlClient bookings;
    private static GraphQlClient payments;
    private static List<String> scheduledFlightIds;

    @BeforeAll
    static void startStack() throws IOException, TimeoutException {
        List<String> missing = Stack.missingImages();
        assertThat(missing).as("images for the system test - run `docker compose build` in the repository root first")
                .isEmpty();
        stack = new Stack();
        stack.start();
        events = EventCollector.connect(stack.rabbitHost(), stack.rabbitPort(), stack.rabbitUsername(),
                stack.rabbitPassword(), OBJECT_MAPPER);
        flights = new GraphQlClient(stack.flightUrl(), OBJECT_MAPPER);
        bookings = new GraphQlClient(stack.bookingUrl(), OBJECT_MAPPER);
        payments = new GraphQlClient(stack.paymentUrl(), OBJECT_MAPPER);
        scheduledFlightIds = flights.query(null, SCHEDULED_FLIGHTS, Map.of()).get("flights").findValuesAsText("id");
        assertThat(scheduledFlightIds).as("SCHEDULED flights in flight-service's seed data").hasSizeGreaterThan(2);
    }

    @AfterAll
    static void stopStack() throws IOException {
        if (events != null) {
            events.close();
        }
        if (stack != null) {
            stack.close();
        }
    }

    // ------------------------------------------------------------------ test 1: booking -> payment -> confirmed

    @Test
    void paidBookingIsConfirmedThroughPaymentCompletedEvent() {
        String flightId = scheduledFlightIds.get(0);
        String seat = freeSeats(flightId).get(0);
        JsonNode booking = createBooking(ANNA, flightId, seat);
        String reference = booking.get("bookingReference").asText();
        assertThat(booking.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        // flight number and price come from the synchronous GraphQL call to the real flight-service
        assertThat(booking.get("price").decimalValue()).isEqualByComparingTo(seatPrice(flightId, seat));
        assertThat(booking.get("seatNumber").asText()).isEqualTo(seat);

        Event created = events.awaitEvent("booking.created", reference);
        assertThat(created.producer()).isEqualTo("booking-service");

        JsonNode payment = pay(ANNA, reference, booking.get("price").decimalValue(), VALID_CARD);
        assertThat(payment.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(payment.get("cardLast4").asText()).isEqualTo("4242");

        // payment-service publishes payment.completed, booking-service consumes it and confirms the booking ...
        Event completed = events.awaitEvent("payment.completed", reference);
        assertThat(completed.producer()).isEqualTo("payment-service");
        assertThat(completed.payload().get("paymentId").asLong()).isEqualTo(payment.get("id").asLong());
        awaitBookingStatus(reference, "CONFIRMED");
        Event confirmed = events.awaitEvent("booking.confirmed", reference);
        assertThat(confirmed.producer()).isEqualTo("booking-service");
        assertThat(confirmed.payload().get("status").asText()).isEqualTo("CONFIRMED");

        // ... and flight-service consumes booking.confirmed and marks the seat as taken
        await().atMost(STATE_TIMEOUT).untilAsserted(() ->
                assertThat(seatAvailable(flightId, seat)).as("seat %s on flight %s available", seat, flightId)
                        .isFalse());
    }

    // ------------------------------------------------------------------ test 2: cancelled flight -> bookings

    @Test
    void cancelledFlightCancelsEveryBookingOnTheFlight() {
        String flightId = scheduledFlightIds.get(2);
        List<String> seats = freeSeats(flightId);
        // one paid (CONFIRMED) booking and one unpaid (PENDING_PAYMENT) booking by two different passengers
        JsonNode paid = createBooking(ANNA, flightId, seats.get(0));
        String paidRef = paid.get("bookingReference").asText();
        pay(ANNA, paidRef, paid.get("price").decimalValue(), VALID_CARD);
        awaitBookingStatus(paidRef, "CONFIRMED");
        String unpaidRef = createBooking(BO, flightId, seats.get(1)).get("bookingReference").asText();
        assertThat(bookingStatus(unpaidRef)).isEqualTo("PENDING_PAYMENT");

        // operations cancels the flight in flight-service ...
        JsonNode cancelled = flights.query(TestKeys.operations(), UPDATE_FLIGHT_STATUS,
                Map.of("id", flightId, "status", "CANCELLED")).get("updateFlightStatus");
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        Event flightCancelled = events.awaitEvents(e -> e.eventType().equals("flight.cancelled")
                && e.payload().path("flightId").asText().equals(flightId), 1).getFirst();
        assertThat(flightCancelled.producer()).isEqualTo("flight-service");

        // ... booking-service consumes flight.cancelled and cancels every booking on the flight, paid or not ...
        for (String reference : List.of(paidRef, unpaidRef)) {
            awaitBookingStatus(reference, "CANCELLED");
            Event bookingCancelled = events.awaitEvent("booking.cancelled", reference);
            assertThat(bookingCancelled.payload().get("reason").asText()).isEqualTo("Flight cancelled");
        }
        assertThat(bookings.query(TestKeys.operations(), BOOKING_BY_REFERENCE, Map.of("reference", paidRef))
                .path("bookingByReference").path("flightStatus").asText()).isEqualTo("CANCELLED");

        // ... and payment-service consumes booking.cancelled and refunds the payment of the paid one
        Event refunded = events.awaitEvent("payment.refunded", paidRef);
        assertThat(refunded.producer()).isEqualTo("payment-service");
        await().atMost(STATE_TIMEOUT).untilAsserted(() ->
                assertThat(paymentStatuses(paidRef)).containsExactly("REFUNDED"));
    }

    // ------------------------------------------------------------------ negative path

    @Test
    void declinedCardFailsPaymentAndCancelsBooking() {
        String flightId = scheduledFlightIds.get(1);
        JsonNode booking = createBooking(ANNA, flightId, freeSeats(flightId).get(0));
        String reference = booking.get("bookingReference").asText();

        JsonNode payment = pay(ANNA, reference, booking.get("price").decimalValue(), DECLINED_CARD);
        assertThat(payment.get("status").asText()).isEqualTo("FAILED");
        assertThat(payment.get("failureReason").asText()).isEqualTo("Insufficient funds");

        Event failed = events.awaitEvent("payment.failed", reference);
        assertThat(failed.producer()).isEqualTo("payment-service");

        // booking-service consumes payment.failed and cancels the booking with the reason from the event
        awaitBookingStatus(reference, "CANCELLED");
        Event cancelled = events.awaitEvent("booking.cancelled", reference);
        assertThat(cancelled.payload().get("reason").asText()).isEqualTo("Payment failed: Insufficient funds");
        // there was no COMPLETED payment, so nothing is refunded
        assertThat(paymentStatuses(reference)).containsExactly("FAILED");
    }

    // ------------------------------------------------------------------ helpers

    /** Free seats on the flight, from the back of the plane (the last seats are the least likely to be taken). */
    private static List<String> freeSeats(String flightId) {
        List<String> seats = flights.query(null, AVAILABLE_SEATS, Map.of("id", flightId)).get("availableSeats")
                .findValuesAsText("seatNumber");
        assertThat(seats).as("free seats on flight %s", flightId).hasSizeGreaterThan(1);
        return seats.reversed();
    }

    private static BigDecimal seatPrice(String flightId, String seat) {
        return flights.query(null, SEAT_PRICE, Map.of("id", flightId, "seat", seat))
                .path("flight").path("seat").path("price").decimalValue();
    }

    private static boolean seatAvailable(String flightId, String seat) {
        return flights.query(null, SEAT_PRICE, Map.of("id", flightId, "seat", seat))
                .path("flight").path("seat").path("isAvailable").asBoolean();
    }

    private static JsonNode createBooking(Passenger passenger, String flightId, String seat) {
        return bookings.query(passenger.token(), CREATE_BOOKING,
                Map.of("flightId", flightId, "seat", seat, "passenger", passenger.input())).get("createBooking");
    }

    private static JsonNode pay(Passenger passenger, String reference, BigDecimal amount, String cardNumber) {
        return payments.query(passenger.token(), PAY, Map.of("reference", reference, "amount", amount,
                "card", cardNumber, "expiry", "12/30", "cvv", "123")).get("pay");
    }

    /** Read as OPERATIONS, which may see every booking (a passenger only sees their own). */
    private static String bookingStatus(String reference) {
        return bookings.query(TestKeys.operations(), BOOKING_BY_REFERENCE, Map.of("reference", reference))
                .path("bookingByReference").path("status").asText();
    }

    private static void awaitBookingStatus(String reference, String expected) {
        await().atMost(STATE_TIMEOUT).untilAsserted(() ->
                assertThat(bookingStatus(reference)).as("status of booking %s", reference).isEqualTo(expected));
    }

    private static List<String> paymentStatuses(String reference) {
        return payments.query(TestKeys.operations(), PAYMENTS_BY_BOOKING, Map.of("reference", reference))
                .get("paymentsByBooking").findValuesAsText("status");
    }
}
