package dk.airport.notification;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns an event envelope into the mail we "send". Pure function, so it is unit tested without RabbitMQ.
 * Throws {@link IllegalArgumentException} for events it cannot turn into a mail (the message then goes to the DLQ).
 */
public final class MailFormatter {

    public record Mail(String to, String subject, String body) {
        public String render() {
            return "To: " + to + "\nSubject: " + subject + "\n\n" + body;
        }
    }

    private MailFormatter() {}

    public static Mail format(JsonNode envelope) {
        String type = text(envelope, "eventType");
        JsonNode p = envelope.path("payload");
        if (p.isMissingNode() || p.isNull()) {
            throw new IllegalArgumentException("Event " + type + " has no payload");
        }
        String reference = required(p, "bookingReference");
        return switch (type) {
            case "booking.confirmed" -> new Mail(email(p),
                    "Booking " + reference + " is confirmed",
                    "Hi " + p.path("passenger").path("firstName").asText("passenger") + ",\n\n"
                            + "your booking " + reference + " is confirmed.\n"
                            + "Flight " + p.path("flightNumber").asText() + ", departure " + p.path("departureTime").asText()
                            + ", seat " + p.path("seatNumber").asText() + ".\n"
                            + "Paid: " + p.path("price").asText() + " " + p.path("currency").asText("DKK") + ".");
            case "booking.cancelled" -> new Mail(email(p),
                    "Booking " + reference + " is cancelled",
                    "Hi " + p.path("passenger").path("firstName").asText("passenger") + ",\n\n"
                            + "your booking " + reference + " on flight " + p.path("flightNumber").asText() + " is cancelled.\n"
                            + "Reason: " + p.path("reason").asText("not given") + ".\n"
                            + "If you have paid, the amount is refunded automatically.");
            // payment.refunded has no e-mail address in its payload (payment-service does not know the passenger),
            // so the mail is addressed to the booking reference.
            case "payment.refunded" -> new Mail("passenger of booking " + reference,
                    "Refund for booking " + reference,
                    "Your payment of " + p.path("amount").asText() + " " + p.path("currency").asText("DKK")
                            + " for booking " + reference + " has been refunded.");
            default -> throw new IllegalArgumentException("No mail template for event type " + type);
        };
    }

    private static String email(JsonNode payload) {
        String email = payload.path("passenger").path("email").asText("");
        if (email.isBlank()) {
            throw new IllegalArgumentException("booking event without passenger.email");
        }
        return email;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing field " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }
}
