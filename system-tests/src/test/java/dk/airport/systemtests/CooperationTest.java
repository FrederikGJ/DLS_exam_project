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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * System-level cooperation test (dev plan DP-27): the real booking-service and payment-service images cooperate
 * through RabbitMQ (asynchronous, via each service's transactional outbox) and one synchronous GraphQL call, observed
 * from the outside only - GraphQL over HTTP in, events on the exchange out. flight-service is replaced by a WireMock
 * stub answering the one query booking-service makes, and Keycloak by a WireMock JWKS endpoint serving the public
 * half of the key that signs the test tokens ({@link TestKeys}). The whole class is skipped, not failed, when the two
 * images have not been built.
 */
class CooperationTest {

    private static final String CREATE_BOOKING = """
            mutation($flightId: ID!, $seat: String!, $passenger: PassengerInput!) {
              createBooking(flightId: $flightId, seatNumber: $seat, passenger: $passenger) {
                id bookingReference status flightId flightNumber seatNumber price currency passenger { email }
              }
            }""";
    private static final String BOOKING_BY_REFERENCE = """
            query($reference: String!) {
              bookingByReference(reference: $reference) { bookingReference status flightNumber seatNumber }
            }""";
    private static final String CANCEL_BOOKING = """
            mutation($reference: String!) { cancelBooking(reference: $reference) { bookingReference status } }""";
    private static final String PAY = """
            mutation($reference: String!, $amount: BigDecimal!, $card: String!, $expiry: String!, $cvv: String!) {
              pay(bookingReference: $reference, amount: $amount, cardNumber: $card, expiry: $expiry, cvv: $cvv) {
                id bookingReference amount currency cardLast4 status failureReason
              }
            }""";
    private static final String PAYMENTS_BY_BOOKING = """
            query($reference: String!) { paymentsByBooking(reference: $reference) { id status } }""";
    private static final String REFUND = "mutation($id: ID!) { refund(paymentId: $id) { id status } }";

    private static final Map<String, Object> ANNA = Map.of(
            "firstName", "Anna", "lastName", "Andersen", "email", "anna@example.com", "passportNumber", "DK123456");
    private static final String VALID_CARD = "4242424242424242";
    /** The simulated gateway declines every card number ending in 0000 ("Insufficient funds"). */
    private static final String DECLINED_CARD = "4000000000000000";
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static Stack stack;
    private static EventCollector events;
    private static GraphQlClient bookings;
    private static GraphQlClient payments;

    @BeforeAll
    static void startStack() throws IOException, TimeoutException {
        assumeTrue(Stack.imagesPresent(), "Skipped: build " + Stack.BOOKING_IMAGE + " and " + Stack.PAYMENT_IMAGE
                + " with `docker compose build` first");
        stack = new Stack();
        stack.start();
        events = EventCollector.connect(stack.rabbitHost(), stack.rabbitPort(), stack.rabbitUsername(),
                stack.rabbitPassword(), OBJECT_MAPPER);
        bookings = new GraphQlClient(stack.bookingUrl(), OBJECT_MAPPER);
        payments = new GraphQlClient(stack.paymentUrl(), OBJECT_MAPPER);
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

    // ------------------------------------------------------------------ happy path

    @Test
    void paidBookingIsConfirmedThroughPaymentCompletedEvent() {
        JsonNode booking = createBooking("12C");
        String reference = booking.get("bookingReference").asText();
        assertThat(reference).hasSize(6);
        assertThat(booking.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        // flight number, seat and price come from the synchronous call to (the stubbed) flight-service
        assertThat(booking.get("flightNumber").asText()).isEqualTo(Stack.FLIGHT_NUMBER);
        assertThat(booking.get("seatNumber").asText()).isEqualTo("12C");
        assertThat(booking.get("price").decimalValue()).isEqualByComparingTo(Stack.SEAT_PRICE);
        assertThat(booking.get("currency").asText()).isEqualTo("DKK");
        assertThat(booking.path("passenger").get("email").asText()).isEqualTo("anna@example.com");

        Event created = events.awaitEvent("booking.created", reference);
        assertThat(created.producer()).isEqualTo("booking-service");
        assertThat(created.eventId()).isNotBlank();
        assertThat(created.occurredAt()).isNotNull();
        assertThat(created.payload().get("status").asText()).isEqualTo("PENDING_PAYMENT");

        JsonNode payment = pay(reference, booking.get("price").decimalValue(), VALID_CARD);
        assertThat(payment.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(payment.get("bookingReference").asText()).isEqualTo(reference);
        assertThat(payment.get("cardLast4").asText()).isEqualTo("4242");
        assertThat(payment.get("failureReason").isNull()).isTrue();

        // payment-service publishes payment.completed, booking-service consumes it and confirms the booking
        Event completed = events.awaitEvent("payment.completed", reference);
        assertThat(completed.producer()).isEqualTo("payment-service");
        assertThat(completed.payload().get("paymentId").asLong()).isEqualTo(payment.get("id").asLong());
        assertThat(completed.payload().get("amount").decimalValue()).isEqualByComparingTo(Stack.SEAT_PRICE);

        awaitBookingStatus(reference, "CONFIRMED");
        Event confirmed = events.awaitEvent("booking.confirmed", reference);
        assertThat(confirmed.producer()).isEqualTo("booking-service");
        assertThat(confirmed.payload().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.payload().get("seatNumber").asText()).isEqualTo("12C");

        // paying twice is rejected by payment-service itself, no event is needed for that
        assertThat(payments.errorCode(TestKeys.passenger(), PAY, payVariables(reference, BigDecimal.ONE, VALID_CARD)))
                .isEqualTo("ALREADY_PAID");
    }

    // ------------------------------------------------------------------ negative path

    @Test
    void declinedCardFailsPaymentAndCancelsBooking() {
        JsonNode booking = createBooking("14A");
        String reference = booking.get("bookingReference").asText();

        JsonNode payment = pay(reference, booking.get("price").decimalValue(), DECLINED_CARD);
        assertThat(payment.get("status").asText()).isEqualTo("FAILED");
        assertThat(payment.get("failureReason").asText()).isEqualTo("Insufficient funds");
        assertThat(payment.get("cardLast4").asText()).isEqualTo("0000");

        Event failed = events.awaitEvent("payment.failed", reference);
        assertThat(failed.producer()).isEqualTo("payment-service");
        assertThat(failed.payload().get("failureReason").asText()).isEqualTo("Insufficient funds");

        // booking-service consumes payment.failed and cancels the booking with the reason from the event
        awaitBookingStatus(reference, "CANCELLED");
        Event cancelled = events.awaitEvent("booking.cancelled", reference);
        assertThat(cancelled.producer()).isEqualTo("booking-service");
        assertThat(cancelled.payload().get("reason").asText()).isEqualTo("Payment failed: Insufficient funds");

        // booking.cancelled reaches payment-service too, but there is no COMPLETED payment to refund
        await().atMost(STATE_TIMEOUT).untilAsserted(() ->
                assertThat(events.received()).extracting(Event::eventType)
                        .contains("booking.cancelled"));
        assertThat(paymentStatuses(reference)).containsExactly("FAILED");
        assertThat(events.received())
                .noneMatch(e -> e.eventType().equals("payment.refunded")
                        && e.payload().path("bookingReference").asText().equals(reference));
    }

    // ------------------------------------------------------------------ cancellation -> refund

    @Test
    void cancellingConfirmedBookingRefundsThePayment() {
        JsonNode booking = createBooking("16F");
        String reference = booking.get("bookingReference").asText();
        JsonNode payment = pay(reference, booking.get("price").decimalValue(), VALID_CARD);
        assertThat(payment.get("status").asText()).isEqualTo("COMPLETED");
        awaitBookingStatus(reference, "CONFIRMED");

        JsonNode cancelled = bookings.query(TestKeys.passenger(), CANCEL_BOOKING, Map.of("reference", reference))
                .get("cancelBooking");
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");

        // booking.cancelled -> payment-service refunds the COMPLETED payment and publishes payment.refunded
        Event refunded = events.awaitEvent("payment.refunded", reference);
        assertThat(refunded.producer()).isEqualTo("payment-service");
        assertThat(refunded.payload().get("paymentId").asLong()).isEqualTo(payment.get("id").asLong());
        assertThat(refunded.payload().get("amount").decimalValue()).isEqualByComparingTo(Stack.SEAT_PRICE);
        await().atMost(STATE_TIMEOUT).untilAsserted(() ->
                assertThat(paymentStatuses(reference)).containsExactly("REFUNDED"));

        // the booking stays CANCELLED: booking-service ignores payment.refunded (only completed/failed matter)
        assertThat(bookingStatus(reference)).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ security and upstream errors

    @Test
    void mutationsNeedATokenAndRefundNeedsOperations() {
        assertThat(bookings.errorCode(null, CREATE_BOOKING, bookingVariables(Stack.FLIGHT_ID, "18B")))
                .isEqualTo("UNAUTHORIZED");
        assertThat(payments.errorCode(null, PAY, payVariables("ABC123", BigDecimal.TEN, VALID_CARD)))
                .isEqualTo("UNAUTHORIZED");
        assertThat(payments.errorCode(TestKeys.passenger(), REFUND, Map.of("id", "1")))
                .isEqualTo("FORBIDDEN");
        // OPERATIONS is allowed to refund; payment 999999 does not exist, so the check gets past authorisation
        assertThat(payments.errorCode(TestKeys.operations(), REFUND, Map.of("id", "999999")))
                .isEqualTo("NOT_FOUND");
        // public query, no token needed
        assertThat(bookings.query(null, BOOKING_BY_REFERENCE, Map.of("reference", "NOSUCH"))
                .get("bookingByReference").isNull()).isTrue();
    }

    @Test
    void unknownFlightIsRejectedByTheSynchronousFlightServiceCall() {
        assertThat(bookings.errorCode(TestKeys.passenger(), CREATE_BOOKING, bookingVariables("99", "1A")))
                .isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode createBooking(String seat) {
        return bookings.query(TestKeys.passenger(), CREATE_BOOKING, bookingVariables(Stack.FLIGHT_ID, seat))
                .get("createBooking");
    }

    private static Map<String, Object> bookingVariables(String flightId, String seat) {
        return Map.of("flightId", flightId, "seat", seat, "passenger", ANNA);
    }

    private static JsonNode pay(String reference, BigDecimal amount, String cardNumber) {
        return payments.query(TestKeys.passenger(), PAY, payVariables(reference, amount, cardNumber)).get("pay");
    }

    private static Map<String, Object> payVariables(String reference, BigDecimal amount, String cardNumber) {
        return Map.of("reference", reference, "amount", amount, "card", cardNumber, "expiry", "12/30", "cvv", "123");
    }

    private static String bookingStatus(String reference) {
        return bookings.query(null, BOOKING_BY_REFERENCE, Map.of("reference", reference))
                .path("bookingByReference").path("status").asText();
    }

    private static void awaitBookingStatus(String reference, String expected) {
        await().atMost(STATE_TIMEOUT).untilAsserted(() ->
                assertThat(bookingStatus(reference)).as("status of booking %s", reference).isEqualTo(expected));
    }

    private static List<String> paymentStatuses(String reference) {
        JsonNode list = payments.query(TestKeys.passenger(), PAYMENTS_BY_BOOKING, Map.of("reference", reference))
                .get("paymentsByBooking");
        return list.findValuesAsText("status");
    }
}
