package dk.airport.booking.query;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.booking.messaging.EventEnvelope;
import dk.airport.booking.query.BookingOverview.Baggage;
import dk.airport.booking.query.BookingOverview.Payment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;

@Component
public class BookingOverviewProjector {

    private final BookingOverviewRepository repository;

    public BookingOverviewProjector(BookingOverviewRepository repository) {
        this.repository = repository;
    }

    public void bookingCreated(long bookingId, long passengerId) {
        repository.refreshPassengers(passengerId);
        repository.refreshBooking(bookingId);
    }

    public void bookingChanged(long bookingId) {
        repository.refreshBooking(bookingId);
    }

    public void flightBookingsChanged(long flightId) {
        repository.refreshFlightBookings(flightId);
    }

    public void paymentObserved(EventEnvelope event, String status) {
        JsonNode payload = event.payload();
        String reference = reference(payload);
        long id = payload.required("paymentId").requireNonNull().asLong();
        OffsetDateTime time = event.occurredAt();
        Payment observation = new Payment(id, status, number(payload, "amount"), text(payload, "currency"),
                text(payload, "cardLast4"), text(payload, "failureReason"),
                "REFUNDED".equals(status) ? null : time, time);
        repository.lockPayments(reference).ifPresent(payments -> {
            payments.merge(id, observation, BookingOverviewProjector::mergePayment);
            repository.updatePayments(reference, payments);
        });
    }

    public void baggageObserved(EventEnvelope event, boolean registration) {
        JsonNode payload = event.payload();
        String reference = reference(payload);
        String tag = payload.required("tagNumber").requireNonNull().asText();
        OffsetDateTime time = event.occurredAt();
        String status = payload.required(registration ? "status" : "newStatus").requireNonNull().asText();
        Baggage observation = new Baggage(tag, status,
                text(payload, "type"), number(payload, "weightKg"),
                registration ? "CHECK_IN" : text(payload, "location"), registration ? time : null, time);
        repository.lockBaggage(reference).ifPresent(baggage -> {
            baggage.merge(tag, observation, BookingOverviewProjector::mergeBaggage);
            repository.updateBaggage(reference, baggage);
        });
    }

    private static Payment mergePayment(Payment current, Payment observed) {
        // A refund is terminal for this payment, even if its completion arrives afterwards.
        Payment latest = "REFUNDED".equals(current.status()) ? current
                : ("REFUNDED".equals(observed.status()) || !observed.statusAt().isBefore(current.statusAt())
                        ? observed : current);
        Payment other = latest == current ? observed : current;
        return new Payment(latest.paymentId(), latest.status(), first(latest.amount(), other.amount()),
                first(latest.currency(), other.currency()), first(latest.cardLast4(), other.cardLast4()),
                latest.failureReason(), first(latest.createdAt(), other.createdAt()), latest.statusAt());
    }

    private static Baggage mergeBaggage(Baggage current, Baggage observed) {
        boolean statusUpdate = observed.registeredAt() == null;
        boolean useObserved = observed.updatedAt().isAfter(current.updatedAt())
                || (statusUpdate && observed.updatedAt().isEqual(current.updatedAt()));
        Baggage latest = useObserved ? observed : current;
        Baggage other = useObserved ? current : observed;
        return new Baggage(latest.tagNumber(), latest.status(), first(latest.type(), other.type()),
                first(latest.weightKg(), other.weightKg()), first(latest.lastLocation(), other.lastLocation()),
                first(current.registeredAt(), observed.registeredAt()), latest.updatedAt());
    }

    private static String reference(JsonNode payload) {
        return payload.required("bookingReference").requireNonNull().asText().trim().toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode payload, String field) {
        return payload.path(field).asText(null);
    }

    private static BigDecimal number(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static <T> T first(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
