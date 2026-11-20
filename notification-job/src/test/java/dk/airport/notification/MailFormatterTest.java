package dk.airport.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailFormatterTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode envelope(String type, String payload) throws Exception {
        return JSON.readTree("""
                {"eventId":"e1","eventType":"%s","occurredAt":"2026-11-19T10:00:00Z","producer":"x","payload":%s}"""
                .formatted(type, payload));
    }

    static final String BOOKING = """
            {"bookingReference":"K7Q2ZP","flightNumber":"SK1501","departureTime":"2026-12-24T14:00:00Z",
             "seatNumber":"12C","price":899.00,"currency":"DKK","status":"%s","reason":%s,
             "passenger":{"firstName":"Anna","lastName":"Jensen","email":"anna@example.com"}}""";

    @Test
    void bookingConfirmedGoesToThePassenger() throws Exception {
        MailFormatter.Mail mail = MailFormatter.format(envelope("booking.confirmed", BOOKING.formatted("CONFIRMED", "null")));
        assertThat(mail.to()).isEqualTo("anna@example.com");
        assertThat(mail.subject()).isEqualTo("Booking K7Q2ZP is confirmed");
        assertThat(mail.body()).contains("SK1501").contains("12C").contains("899.0 DKK");
    }

    @Test
    void bookingCancelledContainsTheReason() throws Exception {
        MailFormatter.Mail mail = MailFormatter.format(envelope("booking.cancelled",
                BOOKING.formatted("CANCELLED", "\"Payment timeout\"")));
        assertThat(mail.subject()).isEqualTo("Booking K7Q2ZP is cancelled");
        assertThat(mail.body()).contains("Reason: Payment timeout.");
    }

    @Test
    void paymentRefundedIsAddressedToTheBookingBecauseTheEventHasNoEmail() throws Exception {
        MailFormatter.Mail mail = MailFormatter.format(envelope("payment.refunded",
                "{\"paymentId\":1,\"bookingReference\":\"K7Q2ZP\",\"amount\":899.00,\"currency\":\"DKK\"}"));
        assertThat(mail.to()).isEqualTo("passenger of booking K7Q2ZP");
        assertThat(mail.body()).contains("899.0 DKK").contains("refunded");
    }

    @Test
    void unknownEventTypeOrMissingDataIsRejected() throws Exception {
        assertThatThrownBy(() -> MailFormatter.format(envelope("baggage.registered", "{\"bookingReference\":\"K7Q2ZP\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailFormatter.format(envelope("booking.confirmed", "{\"bookingReference\":\"K7Q2ZP\"}")))
                .hasMessageContaining("email");
    }
}
