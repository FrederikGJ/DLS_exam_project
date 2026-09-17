package dk.airport.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.booking.domain.Booking;
import dk.airport.booking.graphql.input.PassengerInput;
import dk.airport.booking.messaging.EventEnvelope;
import dk.airport.booking.messaging.IncomingEventHandler;
import dk.airport.booking.query.BookingOverview;
import dk.airport.booking.query.BookingOverviewRepository;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.service.BookingService;
import dk.airport.booking.service.FlightClient;
import dk.airport.booking.service.FlightSeatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Commutative flight snapshot on bookings (dev plan DP-31): the flight.* events for a booking's flight, handled in
 * every possible order, leave the booking - and its read model row - in the same state, and a flight cancellation
 * cancels the booking exactly once. The handler is called directly (in its own transaction, as the RabbitMQ listener
 * does); every permutation uses its own flight and booking.
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

    @Autowired IncomingEventHandler handler;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookings;
    @Autowired BookingOverviewRepository overviews;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean FlightClient flightClient;

    @BeforeEach
    void mockFlightService() {
        when(flightClient.fetchFlightSeat(anyLong(), any())).thenAnswer(inv -> new FlightSeatInfo(
                inv.getArgument(0), "SK" + inv.getArgument(0), T0.plusDays(1), "A12", "SCHEDULED", "DKK",
                inv.getArgument(1), "ECONOMY", true, new BigDecimal("899.00")));
    }

    @Test
    void statusAndGateEndTheSameInAll6Orders() {
        List<Event> events = List.of(
                new Event("flight.status.changed", 1, Map.of("newStatus", "DELAYED", "gate", "A12")),
                new Event("flight.gate.changed", 2, Map.of("oldGate", "A12", "newGate", "B7")),
                new Event("flight.status.changed", 3, Map.of("newStatus", "BOARDING", "gate", "B7")));

        for (List<Event> order : permutations(events)) {
            Booking booking = applyToNewBooking(order);

            assertThat(booking.getFlightStatus()).as("order %s", order).isEqualTo("BOARDING");
            assertThat(booking.getGate()).as("order %s", order).isEqualTo("B7");
            assertThat(booking.getStatus().name()).isEqualTo("PENDING_PAYMENT");
            assertOverviewMatches(booking);
        }
    }

    @Test
    void cancelledFlightCancelsTheBookingOnceInAll24Orders() {
        List<Event> events = List.of(
                new Event("flight.status.changed", 1, Map.of("newStatus", "DELAYED", "gate", "A12")),
                new Event("flight.gate.changed", 2, Map.of("oldGate", "A12", "newGate", "B7")),
                new Event("flight.status.changed", 3, Map.of("newStatus", "CANCELLED", "gate", "B7")),
                new Event("flight.cancelled", 4, Map.of("reason", "Flight cancelled by airline")));

        for (List<Event> order : permutations(events)) {
            Booking booking = applyToNewBooking(order);

            assertThat(booking.getStatus().name()).as("order %s", order).isEqualTo("CANCELLED");
            assertThat(booking.getFlightStatus()).as("order %s", order).isEqualTo("CANCELLED");
            assertThat(booking.getGate()).as("order %s", order).isEqualTo("B7");
            assertThat(cancelledEventsFor(booking.getBookingReference())).as("order %s", order).isEqualTo(1);
            assertOverviewMatches(booking);
        }
    }

    /**
     * Commutative handlers are not automatically safe against two threads: payment.completed and a flight event for
     * the same booking are consumed from different queues. {@code @Version} makes the transaction holding the older
     * copy fail (the listener then retries with a fresh one) instead of writing PENDING_PAYMENT over CONFIRMED.
     */
    @Test
    void aBookingChangedByTwoTransactionsAtOnceIsNotSilentlyOverwritten() {
        String reference = bookingService.createBooking(4999L, "2B",
                new PassengerInput("Lis", "Lock", "lis@example.com", "P7777777", null)).getBookingReference();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            Booking stale = bookings.findByBookingReference(reference).orElseThrow();      // PENDING_PAYMENT
            inner.executeWithoutResult(s ->                                                // commits CONFIRMED
                    bookingService.onPaymentCompleted(reference, 1L, new BigDecimal("899.00"), "DKK"));
            stale.applyFlightSnapshot(null, "C9", T0);                                     // flight event, old copy
        })).isInstanceOf(OptimisticLockingFailureException.class);

        Booking booking = bookings.findByBookingReference(reference).orElseThrow();
        assertThat(booking.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(booking.getGate()).isEqualTo("A12");
        assertThat(overviews.findByBookingReference(reference).orElseThrow().getStatus()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------ helpers

    record Event(String type, int second, Map<String, Object> fields) {
        @Override
        public String toString() { return type + fields.values() + "@" + second; }
    }

    private Booking applyToNewBooking(List<Event> order) {
        long flightId = 5000L + RUN.incrementAndGet();
        String reference = bookingService.createBooking(flightId, "1A",
                new PassengerInput("Per", "Mutation", "per@example.com", "P1234567", null)).getBookingReference();
        for (Event e : order) {
            Map<String, Object> payload = new HashMap<>(e.fields());
            payload.put("flightId", flightId);
            payload.put("flightNumber", "SK" + flightId);
            handler.handle(new EventEnvelope(UUID.randomUUID().toString(), e.type(), T0.plusSeconds(e.second()),
                    "flight-service", objectMapper.valueToTree(payload)));
        }
        return bookings.findByBookingReference(reference).orElseThrow();
    }

    private void assertOverviewMatches(Booking booking) {
        BookingOverview overview = overviews.findByBookingReference(booking.getBookingReference()).orElseThrow();
        assertThat(overview.getStatus()).isEqualTo(booking.getStatus().name());
        assertThat(overview.getFlightStatus()).isEqualTo(booking.getFlightStatus());
        assertThat(overview.getGate()).isEqualTo(booking.getGate());
    }

    private int cancelledEventsFor(String reference) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = 'booking.cancelled'"
                + " and payload -> 'payload' ->> 'bookingReference' = ?", Integer.class, reference);
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
