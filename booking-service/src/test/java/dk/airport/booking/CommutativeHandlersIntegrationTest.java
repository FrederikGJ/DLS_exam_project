package dk.airport.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.BookingStatus;
import dk.airport.booking.domain.Passenger;
import dk.airport.booking.messaging.EventEnvelope;
import dk.airport.booking.messaging.OutboxEventRepository;
import dk.airport.booking.messaging.ProcessedEventRepository;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.repository.PassengerRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Ticket 10: flight.* and payment.* events delivered through RabbitMQ out of order give the same booking state
 * as in-order delivery.
 */
@SpringBootTest
@Testcontainers
class CommutativeHandlersIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired BookingRepository bookings;
    @Autowired PassengerRepository passengers;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired OutboxEventRepository outbox;

    @Test
    void flightEventsInReverseOrderGiveTheNewestGateAndStatus() throws Exception {
        Booking booking = saveBooking("CMFL01", 101L, "3A");
        OffsetDateTime t = OffsetDateTime.now().plusSeconds(10);

        // in order: status DELAYED (gate B7) at t, gate B9 at t+1. Delivered newest first:
        awaitProcessed(publish("flight.gate.changed", t.plusMinutes(1), "flight-service",
                Map.of("flightId", 101, "flightNumber", "SK1501", "oldGate", "B7", "newGate", "B9")));
        awaitProcessed(publish("flight.status.changed", t, "flight-service",
                Map.of("flightId", 101, "flightNumber", "SK1501", "oldStatus", "SCHEDULED", "newStatus", "DELAYED", "gate", "B7")));

        Booking after = bookings.findById(booking.getId()).orElseThrow();
        assertThat(after.getFlightStatus()).isEqualTo("DELAYED");
        assertThat(after.getGate()).isEqualTo("B9");
    }

    @Test
    void paymentFailedDeliveredAfterPaymentCompletedEndsCancelled() throws Exception {
        saveBooking("CMPY01", 102L, "3B");
        OffsetDateTime t = OffsetDateTime.now();

        // in order the failed attempt (t) comes before the successful one (t+1): CANCELLED + refund.
        // Delivered the other way round, the end state must be the same.
        awaitProcessed(publish("payment.completed", t.plusMinutes(1), "payment-service",
                Map.of("paymentId", 2, "bookingReference", "CMPY01", "amount", 899.00, "currency", "DKK", "cardLast4", "4242")));
        assertThat(bookings.findByBookingReference("CMPY01").orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        awaitProcessed(publish("payment.failed", t, "payment-service",
                Map.of("paymentId", 1, "bookingReference", "CMPY01", "amount", 899.00, "currency", "DKK",
                        "cardLast4", "0000", "failureReason", "Insufficient funds")));

        assertThat(bookings.findByBookingReference("CMPY01").orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        // booking.cancelled is what makes payment-service refund the completed payment
        assertThat(outbox.findAll()).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("booking.cancelled");
            assertThat(e.getPayload()).contains("CMPY01");
        });
    }

    // ------------------------------------------------------------ helpers

    private Booking saveBooking(String ref, Long flightId, String seat) {
        Passenger p = passengers.save(new Passenger("Anna", "Jensen", ref.toLowerCase() + "@example.com", "P1234567", null));
        return bookings.save(new Booking(ref, p, flightId, "SK1501", OffsetDateTime.now().plusDays(1), "A12", "SCHEDULED",
                seat, new BigDecimal("899.00"), "DKK"));
    }

    private String publish(String type, OffsetDateTime occurredAt, String producer, Map<String, Object> payload) throws Exception {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope env = new EventEnvelope(eventId, type, occurredAt, producer, objectMapper.valueToTree(payload));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
        return eventId;
    }

    private void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> processedEvents.existsById(eventId));
    }
}
