package dk.airport.notification.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MailRenderer renderer = new MailRenderer();

    /** Envelope + booking payload exactly as booking-service publishes it (docs/events.md). */
    static ObjectNode bookingEvent(String eventType, String status) {
        ObjectNode event = JSON.createObjectNode()
                .put("eventId", "3f1c2a8e-8d0e-4f3c-9a6b-1d2e3f4a5b6c")
                .put("eventType", eventType)
                .put("occurredAt", "2026-09-11T10:15:30.123Z")
                .put("producer", "booking-service");
        ObjectNode payload = event.putObject("payload")
                .put("bookingId", 1)
                .put("bookingReference", "K7Q2ZP")
                .put("flightId", 1)
                .put("flightNumber", "SK1501")
                .put("departureTime", "2026-09-11T14:00:00Z")
                .put("seatNumber", "12C")
                .put("price", new BigDecimal("899.00"))
                .put("currency", "DKK")
                .put("status", status);
        payload.putObject("passenger")
                .put("firstName", "Anna")
                .put("lastName", "Jensen")
                .put("email", "anna@example.com");
        payload.putNull("reason");
        return event;
    }

    private Mail render(JsonNode event) throws Exception {
        JsonNode parsed = renderer.parse(JSON.writeValueAsBytes(event));
        Optional<Mail> mail = renderer.render(parsed);
        assertThat(mail).isPresent();
        return mail.get();
    }

    @Test
    void bookingCreated_asksForPayment() throws Exception {
        Mail mail = render(bookingEvent("booking.created", "PENDING_PAYMENT"));

        assertThat(mail.to()).isEqualTo("anna@example.com");
        assertThat(mail.eventId()).isEqualTo("3f1c2a8e-8d0e-4f3c-9a6b-1d2e3f4a5b6c");
        assertThat(mail.eventType()).isEqualTo("booking.created");
        assertThat(mail.subject()).isEqualTo("Din booking K7Q2ZP er modtaget - afventer betaling");
        assertThat(mail.body())
                .startsWith("Hej Anna,")
                .contains("først bekræftet", "når betalingen er gennemført")
                .contains("Bookingreference: K7Q2ZP")
                .contains("Fly:              SK1501")
                // 14:00 UTC is 16:00 Danish summer time
                .contains("Afgang:           11. september 2026 kl. 16:00 (dansk tid)")
                .contains("Sæde:             12C")
                .contains("Pris:             899,00 DKK")
                .endsWith("Venlig hilsen\nLufthavnen");
    }

    @Test
    void bookingConfirmed_confirmsThePayment() throws Exception {
        Mail mail = render(bookingEvent("booking.confirmed", "CONFIRMED"));

        assertThat(mail.subject()).isEqualTo("Din booking K7Q2ZP er bekræftet");
        assertThat(mail.body())
                .contains("Vi har modtaget din betaling, og din booking er nu bekræftet.")
                .contains("Bookingreference: K7Q2ZP", "God rejse!");
    }

    @Test
    void bookingCancelled_includesTheReason() throws Exception {
        ObjectNode event = bookingEvent("booking.cancelled", "CANCELLED");
        ((ObjectNode) event.get("payload")).put("reason", "Payment failed");

        Mail mail = render(event);

        assertThat(mail.subject()).isEqualTo("Din booking K7Q2ZP er annulleret");
        assertThat(mail.body())
                .contains("Din booking er desværre annulleret.")
                .contains("Årsag: Payment failed")
                .contains("refunderet");
    }

    @Test
    void bookingCancelled_withoutReason_saysSo() throws Exception {
        Mail mail = render(bookingEvent("booking.cancelled", "CANCELLED"));

        assertThat(mail.body()).contains("Årsag: ikke oplyst");
    }

    @Test
    void bookingCheckedIn_namesTheFlight() throws Exception {
        Mail mail = render(bookingEvent("booking.checkedin", "CHECKED_IN"));

        assertThat(mail.subject()).isEqualTo("Du er checket ind på fly SK1501");
        assertThat(mail.body()).contains("Du er nu checket ind.", "Sæde:             12C");
    }

    @Test
    void winterDeparture_isShownInDanishStandardTime_andLargePricesGetThousandSeparators() throws Exception {
        ObjectNode event = bookingEvent("booking.confirmed", "CONFIRMED");
        ((ObjectNode) event.get("payload"))
                .put("departureTime", "2026-12-24T07:05:00Z")
                .put("price", new BigDecimal("12999.5"));

        Mail mail = render(event);

        assertThat(mail.body())
                .contains("Afgang:           24. december 2026 kl. 08:05 (dansk tid)")
                .contains("Pris:             12.999,50 DKK");
    }

    @Test
    void unknownFieldsAreIgnored_andOptionalFieldsCanBeMissing() throws Exception {
        ObjectNode event = bookingEvent("booking.confirmed", "CONFIRMED");
        event.put("schemaVersion", 2);
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("loyaltyTier", "GOLD");
        payload.remove("seatNumber");
        payload.remove("price");
        ((ObjectNode) payload.get("passenger")).remove("firstName");

        Mail mail = render(event);

        assertThat(mail.body())
                .startsWith("Hej,")
                .doesNotContain("Sæde:", "Pris:")
                .contains("Bookingreference: K7Q2ZP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"booking.x.v2", "booking.seat.changed", "payment.completed"})
    void eventTypesWithoutTemplate_renderNothing(String eventType) throws Exception {
        ObjectNode event = bookingEvent(eventType, "CONFIRMED");
        event.remove("payload");            // not even looked at

        JsonNode parsed = renderer.parse(JSON.writeValueAsBytes(event));

        assertThat(renderer.render(parsed)).isEmpty();
        assertThat(MailRenderer.isSupported(eventType)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{not json", "[1, 2, 3]", "\"booking.created\"", "null",
        "{\"eventType\": \"booking.created\"}", "{\"eventId\": \"e-1\"}",
        "{\"eventId\": \"e-1\", \"eventType\": \" \"}"})
    void bodiesWithoutAValidEnvelope_areMalformed(String body) {
        assertThatThrownBy(() -> renderer.parse(body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    void missingPayload_isMalformed() throws Exception {
        ObjectNode event = bookingEvent("booking.created", "PENDING_PAYMENT");
        event.remove("payload");
        JsonNode parsed = renderer.parse(JSON.writeValueAsBytes(event));

        assertThatThrownBy(() -> renderer.render(parsed))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("payload");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bookingReference", "flightNumber", "departureTime"})
    void missingRequiredPayloadField_isMalformed(String field) throws Exception {
        ObjectNode event = bookingEvent("booking.confirmed", "CONFIRMED");
        ((ObjectNode) event.get("payload")).remove(field);
        JsonNode parsed = renderer.parse(JSON.writeValueAsBytes(event));

        assertThatThrownBy(() -> renderer.render(parsed))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("payload." + field);
    }

    @Test
    void missingRecipient_isMalformed() throws Exception {
        ObjectNode event = bookingEvent("booking.checkedin", "CHECKED_IN");
        ((ObjectNode) event.get("payload").get("passenger")).remove("email");
        JsonNode parsed = renderer.parse(JSON.writeValueAsBytes(event));

        assertThatThrownBy(() -> renderer.render(parsed))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("payload.passenger.email");
    }

    @Test
    void departureTimeThatIsNotIso8601_isMalformed() throws Exception {
        ObjectNode event = bookingEvent("booking.created", "PENDING_PAYMENT");
        ((ObjectNode) event.get("payload")).put("departureTime", "tomorrow morning");
        JsonNode parsed = renderer.parse(JSON.writeValueAsBytes(event));

        assertThatThrownBy(() -> renderer.render(parsed))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("departureTime");
    }
}
