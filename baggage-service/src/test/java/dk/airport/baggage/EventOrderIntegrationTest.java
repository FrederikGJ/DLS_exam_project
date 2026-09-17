package dk.airport.baggage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.domain.BaggageStatus;
import dk.airport.baggage.domain.BaggageType;
import dk.airport.baggage.domain.BookingSnapshot;
import dk.airport.baggage.messaging.BookingEventHandler;
import dk.airport.baggage.messaging.EventEnvelope;
import dk.airport.baggage.messaging.FlightEventHandler;
import dk.airport.baggage.repository.BaggageRepository;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import dk.airport.baggage.service.BaggageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Commutative booking snapshot (dev plan DP-31): booking.* and flight.cancelled events for one booking, handled in
 * every possible order, give the same snapshot. The handlers are called directly (each in its own transaction, as
 * the RabbitMQ listeners do); every permutation uses its own booking and flight.
 */
@SpringBootTest
@Testcontainers
class EventOrderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final OffsetDateTime T0 = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.MICROS);
    static final AtomicInteger RUN = new AtomicInteger();

    @Autowired BookingEventHandler bookingEvents;
    @Autowired FlightEventHandler flightEvents;
    @Autowired BookingSnapshotRepository snapshots;
    @Autowired ObjectMapper objectMapper;
    @Autowired BaggageService baggageService;
    @Autowired BaggageRepository baggage;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void fullLifecycleEndsCancelledInAll24Orders() {
        assertEveryOrderEndsIn("CANCELLED", List.of("booking.created", "booking.confirmed", "booking.checkedin",
                "booking.cancelled"));
    }

    @Test
    void checkedInBookingStaysCheckedInWhateverArrivesLate() {
        assertEveryOrderEndsIn("CHECKED_IN", List.of("booking.created", "booking.confirmed", "booking.checkedin"));
    }

    /**
     * A flight is cancelled while the passenger checks in: flight-service's flight.cancelled and booking-service's
     * booking.checkedin and booking.cancelled (booking-service cancels every booking on the flight) arrive in any
     * order, also flight.cancelled before the snapshot exists. All 120 orders end CANCELLED.
     */
    @Test
    void flightCancellationDuringCheckInEndsCancelledInAll120Orders() {
        assertEveryOrderEndsIn("CANCELLED", List.of("booking.created", "booking.confirmed", "booking.checkedin",
                "flight.cancelled", "booking.cancelled"));
    }

    /**
     * Commutative handlers are not automatically safe against two threads: flight.cancelled sends a bag to the return
     * desk while an operator updates it. {@code @Version} makes the transaction holding the older copy fail instead of
     * overwriting the change that committed first.
     */
    @Test
    void aBagChangedByTwoTransactionsAtOnceIsNotSilentlyOverwritten() {
        bookingEvents.handle(envelope("booking.confirmed", "LOCK01", 8001L, T0));
        String tag = baggageService.register("LOCK01", new BigDecimal("20.0"), BaggageType.CHECKED).getTagNumber();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            Baggage stale = baggage.findByTagNumberIgnoreCase(tag).orElseThrow();
            inner.executeWithoutResult(s -> baggageService.updateStatus(tag, BaggageStatus.LOADED, "Belt 4"));
            stale.moveTo(BaggageStatus.REGISTERED, "RETURN_DESK");       // what a handler with the old copy would do
        })).isInstanceOf(OptimisticLockingFailureException.class);

        Baggage bag = baggage.findByTagNumberIgnoreCase(tag).orElseThrow();
        assertThat(bag.getStatus()).isEqualTo(BaggageStatus.LOADED);
        assertThat(bag.getLastLocation()).isEqualTo("Belt 4");
    }

    // ------------------------------------------------------------------ helpers

    private void assertEveryOrderEndsIn(String expected, List<String> eventTypes) {
        List<List<String>> orders = permutations(eventTypes);
        for (List<String> order : orders) {
            int run = RUN.incrementAndGet();
            String reference = String.format("P%05d", run);
            long flightId = 9000L + run;
            for (String type : order) {
                // the event's time is its position in the lifecycle, not in this order
                OffsetDateTime occurredAt = T0.plusSeconds(eventTypes.indexOf(type));
                EventEnvelope envelope = envelope(type, reference, flightId, occurredAt);
                if (type.startsWith("flight.")) {
                    flightEvents.handle(envelope);
                } else {
                    bookingEvents.handle(envelope);
                }
            }
            BookingSnapshot snapshot = snapshots.findById(reference).orElseThrow();
            assertThat(snapshot.getStatus()).as("order %s", order).isEqualTo(expected);
            assertThat(snapshot.getEventOccurredAt().toInstant())
                    .isEqualTo(T0.plusSeconds(eventTypes.size() - 1).toInstant());
        }
        assertThat(orders).hasSize(factorial(eventTypes.size()));
    }

    private EventEnvelope envelope(String type, String reference, long flightId, OffsetDateTime occurredAt) {
        Map<String, Object> payload = type.startsWith("flight.")
                ? Map.of("flightId", flightId, "flightNumber", "PT" + flightId, "reason", "test")
                : Map.of("bookingReference", reference, "flightId", flightId, "flightNumber", "PT" + flightId,
                        "status", statusOf(type), "passenger", Map.of("firstName", "Per", "lastName", "Mutation"));
        return new EventEnvelope(UUID.randomUUID().toString(), type, occurredAt,
                type.startsWith("flight.") ? "flight-service" : "booking-service", objectMapper.valueToTree(payload));
    }

    private static String statusOf(String bookingEventType) {
        return switch (bookingEventType) {
            case "booking.confirmed" -> "CONFIRMED";
            case "booking.checkedin" -> "CHECKED_IN";
            case "booking.cancelled" -> "CANCELLED";
            default -> "PENDING_PAYMENT";
        };
    }

    private static int factorial(int n) {
        return n <= 1 ? 1 : n * factorial(n - 1);
    }

    static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }
}
