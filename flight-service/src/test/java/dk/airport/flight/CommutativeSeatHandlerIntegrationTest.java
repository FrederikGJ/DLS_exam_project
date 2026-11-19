package dk.airport.flight;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.flight.domain.Seat;
import dk.airport.flight.messaging.EventEnvelope;
import dk.airport.flight.messaging.ProcessedEventRepository;
import dk.airport.flight.repository.FlightRepository;
import dk.airport.flight.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Ticket 10: booking events delivered through RabbitMQ in reverse order end in the same seat state as in order.
 */
@SpringBootTest
@Testcontainers
class CommutativeSeatHandlerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-19T10:00:00Z");

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired FlightRepository flightRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired ProcessedEventRepository processedEvents;

    @Test
    void cancelledBeforeConfirmedLeavesTheSeatFree() throws Exception {
        Long flightId = flightRepository.findAll().get(0).getId();

        // reverse order: the cancel (newer) arrives before the confirm (older)
        String cancel = publish("booking.cancelled", T1.plusMinutes(1), flightId, "6C", "AAAAAA");
        awaitProcessed(cancel);
        String confirm = publish("booking.confirmed", T1, flightId, "6C", "AAAAAA");
        awaitProcessed(confirm);

        Seat seat = seat(flightId, "6C");
        assertThat(seat.isAvailable()).isTrue();
        assertThat(seat.getHeldByBooking()).isEqualTo("AAAAAA");
    }

    @Test
    void rebookedSeatIsNotFreedByAnOlderBookingsCancel() throws Exception {
        Long flightId = flightRepository.findAll().get(0).getId();

        // B's confirm (newest) first, then A's confirm and cancel (older)
        awaitProcessed(publish("booking.confirmed", T1.plusMinutes(2), flightId, "6D", "BBBBBB"));
        awaitProcessed(publish("booking.confirmed", T1, flightId, "6D", "AAAAAA"));
        awaitProcessed(publish("booking.cancelled", T1.plusMinutes(1), flightId, "6D", "AAAAAA"));

        Seat seat = seat(flightId, "6D");
        assertThat(seat.isAvailable()).isFalse();
        assertThat(seat.getHeldByBooking()).isEqualTo("BBBBBB");
    }

    private Seat seat(Long flightId, String seatNumber) {
        return seatRepository.findByFlightIdAndSeatNumberIgnoreCase(flightId, seatNumber).orElseThrow();
    }

    private String publish(String type, OffsetDateTime occurredAt, Long flightId, String seat, String ref) throws Exception {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope env = new EventEnvelope(eventId, type, occurredAt, "booking-service", objectMapper.valueToTree(
                Map.of("bookingReference", ref, "flightId", flightId, "seatNumber", seat)));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
        return eventId;
    }

    private void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> processedEvents.existsById(eventId));
    }
}
