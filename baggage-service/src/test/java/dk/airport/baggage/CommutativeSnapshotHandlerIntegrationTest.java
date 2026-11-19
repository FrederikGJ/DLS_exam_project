package dk.airport.baggage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.baggage.messaging.EventEnvelope;
import dk.airport.baggage.messaging.ProcessedEventRepository;
import dk.airport.baggage.repository.BookingSnapshotRepository;
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
 * Ticket 10: booking events delivered through RabbitMQ in reverse order give the same booking_snapshot as in order.
 */
@SpringBootTest
@Testcontainers
class CommutativeSnapshotHandlerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-19T10:00:00Z");

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired BookingSnapshotRepository snapshots;
    @Autowired ProcessedEventRepository processedEvents;

    @Test
    void cancelledBeforeConfirmedEndsCancelled() throws Exception {
        awaitProcessed(publish("booking.cancelled", T1.plusMinutes(2), "CANC01", "CANCELLED"));
        awaitProcessed(publish("booking.confirmed", T1.plusMinutes(1), "CANC01", "CONFIRMED"));
        awaitProcessed(publish("booking.created", T1, "CANC01", "PENDING_PAYMENT"));

        assertThat(snapshots.findById("CANC01").orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void reverseOrderEndsWithTheNewestStatus() throws Exception {
        awaitProcessed(publish("booking.checkedin", T1.plusMinutes(2), "CHKIN1", "CHECKED_IN"));
        awaitProcessed(publish("booking.confirmed", T1.plusMinutes(1), "CHKIN1", "CONFIRMED"));
        awaitProcessed(publish("booking.created", T1, "CHKIN1", "PENDING_PAYMENT"));

        assertThat(snapshots.findById("CHKIN1").orElseThrow().getStatus()).isEqualTo("CHECKED_IN");
    }

    private String publish(String type, OffsetDateTime occurredAt, String ref, String status) throws Exception {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope env = new EventEnvelope(eventId, type, occurredAt, "booking-service", objectMapper.valueToTree(Map.of(
                "bookingId", 1, "bookingReference", ref, "flightId", 1, "flightNumber", "SK1501",
                "seatNumber", "12C", "status", status,
                "passenger", Map.of("firstName", "Anna", "lastName", "Jensen", "email", "anna@example.com"))));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
        return eventId;
    }

    private void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> processedEvents.existsById(eventId));
    }
}
