package dk.airport.flight;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.flight.domain.Seat;
import dk.airport.flight.messaging.BookingEventHandler;
import dk.airport.flight.messaging.EventEnvelope;
import dk.airport.flight.repository.FlightRepository;
import dk.airport.flight.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commutative seat handling (dev plan DP-31): the same booking events for a seat, handled in every possible order,
 * leave the seat in the same state. The handler is called directly (in its own transaction, as the RabbitMQ listener
 * does) so the order is exactly the one under test; each permutation uses its own seat.
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

    @Autowired BookingEventHandler handler;
    @Autowired FlightRepository flights;
    @Autowired SeatRepository seats;
    @Autowired ObjectMapper objectMapper;

    @Test
    void seatTakenAgainByANewBookingEndsTakenInEveryOrder() {
        Iterator<Seat> free = freeSeats();
        // booking A takes the seat, A is cancelled, booking B takes the same seat
        for (List<Event> order : permutations(List.of(
                new Event("booking.confirmed", "AAAAAA", 1), new Event("booking.cancelled", "AAAAAA", 2),
                new Event("booking.confirmed", "BBBBBB", 3)))) {
            Seat seat = free.next();
            order.forEach(e -> handler.handle(envelope(e, seat)));

            Seat result = seats.findById(seat.getId()).orElseThrow();
            assertThat(result.isAvailable()).as("order %s", order).isFalse();
            assertThat(result.getAvailabilityChangedAt().toInstant()).isEqualTo(T0.plusSeconds(3).toInstant());
        }
    }

    @Test
    void cancelledBookingFreesTheSeatInEveryOrder() {
        Iterator<Seat> free = freeSeats();
        for (List<Event> order : permutations(List.of(
                new Event("booking.confirmed", "CCCCCC", 1), new Event("booking.cancelled", "CCCCCC", 2)))) {
            Seat seat = free.next();
            order.forEach(e -> handler.handle(envelope(e, seat)));

            assertThat(seats.findById(seat.getId()).orElseThrow().isAvailable()).as("order %s", order).isTrue();
        }
    }

    // ------------------------------------------------------------------ helpers

    record Event(String type, String bookingReference, int second) {
        @Override
        public String toString() { return type + "@" + second; }
    }

    private Iterator<Seat> freeSeats() {
        Long flightId = flights.findAll().get(0).getId();
        return seats.findAvailableByFlightIdOrdered(flightId).stream()
                .filter(s -> s.getAvailabilityChangedAt() == null)
                .iterator();
    }

    private EventEnvelope envelope(Event e, Seat seat) {
        Map<String, Object> payload = Map.of("bookingReference", e.bookingReference(),
                "flightId", seat.getFlight().getId(), "seatNumber", seat.getSeatNumber(), "reason", "test");
        return new EventEnvelope(UUID.randomUUID().toString(), e.type(), T0.plusSeconds(e.second()),
                "booking-service", objectMapper.valueToTree(payload));
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
