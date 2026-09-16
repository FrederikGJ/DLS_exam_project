package dk.airport.notification.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a booking event (envelope and payload as documented in docs/events.md) into a Danish e-mail.
 * <pre>
 *  booking.created    "Din booking K7Q2ZP er modtaget - afventer betaling"
 *  booking.confirmed  "Din booking K7Q2ZP er bekræftet"
 *  booking.cancelled  "Din booking K7Q2ZP er annulleret"      (body includes payload.reason)
 *  booking.checkedin  "Du er checket ind på fly SK1501"
 *  any other type     no mail (Optional.empty) - the job acknowledges and skips it
 * </pre>
 * The JSON is read as a tree ({@code path(...)}), so unknown fields in the envelope or payload are ignored and a
 * producer can add fields without breaking the job. Required: {@code eventId}, {@code eventType}, and for the four
 * types above {@code payload.bookingReference}, {@code payload.flightNumber}, {@code payload.departureTime} (ISO-8601)
 * and {@code payload.passenger.email}. Anything else is optional and left out of the mail when missing.
 * Departure times are shown in Danish local time (Europe/Copenhagen), prices with Danish number formatting.
 */
public final class MailRenderer {

    public static final String BOOKING_CREATED = "booking.created";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String BOOKING_CHECKED_IN = "booking.checkedin";

    private static final Locale DANISH = Locale.forLanguageTag("da-DK");
    private static final ZoneId LOCAL_TIME = ZoneId.of("Europe/Copenhagen");
    private static final DateTimeFormatter DEPARTURE_FORMAT =
            DateTimeFormatter.ofPattern("d. MMMM yyyy 'kl.' HH:mm", DANISH);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parses the raw message body into a JSON tree and checks the envelope fields every event must have.
     *
     * @param body the AMQP message body (UTF-8 JSON)
     * @return the envelope as a JSON object
     * @throws MalformedEventException if the body is not a JSON object with a non-blank eventId and eventType
     */
    public JsonNode parse(byte[] body) throws MalformedEventException {
        JsonNode event;
        try {
            event = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("body is not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new MalformedEventException("body could not be read: " + e.getMessage(), e);
        }
        if (event == null || !event.isObject()) {
            throw new MalformedEventException("body is not a JSON object");
        }
        required(event, "eventId", "eventId");
        required(event, "eventType", "eventType");
        return event;
    }

    /**
     * Renders the mail for a parsed event.
     *
     * @param event an envelope returned by {@link #parse(byte[])}
     * @return the mail, or empty if there is no template for the event type
     * @throws MalformedEventException if a field the template needs is missing or invalid
     */
    public Optional<Mail> render(JsonNode event) throws MalformedEventException {
        String eventType = required(event, "eventType", "eventType");
        String eventId = required(event, "eventId", "eventId");
        if (!isSupported(eventType)) {
            return Optional.empty();
        }
        JsonNode payload = event.path("payload");
        if (!payload.isObject()) {
            throw new MalformedEventException("payload is missing or not a JSON object");
        }
        BookingDetails booking = BookingDetails.from(payload);
        String subject = switch (eventType) {
            case BOOKING_CREATED -> "Din booking " + booking.reference() + " er modtaget - afventer betaling";
            case BOOKING_CONFIRMED -> "Din booking " + booking.reference() + " er bekræftet";
            case BOOKING_CANCELLED -> "Din booking " + booking.reference() + " er annulleret";
            default -> "Du er checket ind på fly " + booking.flightNumber();
        };
        return Optional.of(new Mail(eventId, eventType, booking.email(), subject, body(eventType, booking, payload)));
    }

    /**
     * Whether a mail template exists for the event type.
     *
     * @param eventType the envelope's eventType
     * @return true for the four booking events the job sends mails for
     */
    public static boolean isSupported(String eventType) {
        return switch (eventType) {
            case BOOKING_CREATED, BOOKING_CONFIRMED, BOOKING_CANCELLED, BOOKING_CHECKED_IN -> true;
            default -> false;
        };
    }

    private static String body(String eventType, BookingDetails booking, JsonNode payload) {
        List<String> lines = new ArrayList<>();
        lines.add(booking.firstName().map(name -> "Hej " + name + ",").orElse("Hej,"));
        lines.add("");
        switch (eventType) {
            case BOOKING_CREATED -> {
                lines.add("Tak for din booking. Vi har reserveret dit sæde, men bookingen er først bekræftet,");
                lines.add("når betalingen er gennemført.");
            }
            case BOOKING_CONFIRMED -> lines.add("Vi har modtaget din betaling, og din booking er nu bekræftet.");
            case BOOKING_CANCELLED -> {
                lines.add("Din booking er desværre annulleret.");
                lines.add("Årsag: " + optionalText(payload, "reason").orElse("ikke oplyst"));
            }
            default -> {
                lines.add("Du er nu checket ind. Husk dit pas, og vær ved gaten i god tid før afgang.");
            }
        }
        lines.add("");
        lines.add("Bookingreference: " + booking.reference());
        lines.add("Fly:              " + booking.flightNumber());
        lines.add("Afgang:           " + DEPARTURE_FORMAT.format(booking.departure()) + " (dansk tid)");
        booking.seatNumber().ifPresent(seat -> lines.add("Sæde:             " + seat));
        booking.price().ifPresent(price -> lines.add("Pris:             " + price));
        lines.add("");
        if (BOOKING_CANCELLED.equals(eventType)) {
            lines.add("Har du allerede betalt, bliver beløbet automatisk refunderet til dit kort.");
            lines.add("");
        } else if (BOOKING_CONFIRMED.equals(eventType) || BOOKING_CHECKED_IN.equals(eventType)) {
            lines.add("God rejse!");
            lines.add("");
        }
        lines.add("Venlig hilsen");
        lines.add("Lufthavnen");
        return String.join("\n", lines);
    }

    private static String required(JsonNode node, String field, String path) throws MalformedEventException {
        return optionalText(node, field)
                .orElseThrow(() -> new MalformedEventException(path + " is missing or blank"));
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText().strip());
    }

    /** The payload fields the templates use, validated once. */
    private record BookingDetails(String reference, String flightNumber, OffsetDateTime departure, String email,
                                  Optional<String> firstName, Optional<String> seatNumber, Optional<String> price) {

        static BookingDetails from(JsonNode payload) throws MalformedEventException {
            String departureText = required(payload, "departureTime", "payload.departureTime");
            OffsetDateTime departure;
            try {
                departure = OffsetDateTime.parse(departureText).atZoneSameInstant(LOCAL_TIME).toOffsetDateTime();
            } catch (DateTimeParseException e) {
                throw new MalformedEventException("payload.departureTime is not an ISO-8601 timestamp: "
                        + departureText, e);
            }
            JsonNode passenger = payload.path("passenger");
            return new BookingDetails(
                    required(payload, "bookingReference", "payload.bookingReference"),
                    required(payload, "flightNumber", "payload.flightNumber"),
                    departure,
                    required(passenger, "email", "payload.passenger.email"),
                    optionalText(passenger, "firstName"),
                    optionalText(payload, "seatNumber"),
                    price(payload));
        }

        /** "1.299,50 DKK"; left out unless both a numeric price and a currency are present. */
        private static Optional<String> price(JsonNode payload) {
            JsonNode price = payload.path("price");
            Optional<String> currency = optionalText(payload, "currency");
            if (!price.isNumber() || currency.isEmpty()) {
                return Optional.empty();
            }
            BigDecimal amount = price.decimalValue();
            return Optional.of(String.format(DANISH, "%,.2f %s", amount, currency.get()));
        }
    }
}
