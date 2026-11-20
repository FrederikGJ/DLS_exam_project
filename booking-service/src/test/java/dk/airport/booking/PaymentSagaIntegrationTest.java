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
import dk.airport.booking.service.BookingCommandService;
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
 * Ticket 11: the two compensations in saga Flow A (booking + payment).
 * The payment timeout is 3 s here (15 min in the real config) and checked every 500 ms.
 */
@SpringBootTest(properties = {
    "app.saga.payment-timeout=3s",
    "app.saga.payment-timeout-check-interval-ms=500"
})
@Testcontainers
class PaymentSagaIntegrationTest {

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
    void unpaidBookingIsCancelledAfterTheTimeoutAndPublishesBookingCancelled() {
        saveBooking("SAGA01", "4A");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(status("SAGA01")).isEqualTo(BookingStatus.CANCELLED));
        assertThat(cancelledEventsWithReason("SAGA01", BookingCommandService.PAYMENT_TIMEOUT_REASON)).isEqualTo(1);
    }

    @Test
    void paidInTimeIsNotCancelled() throws Exception {
        saveBooking("SAGA02", "4B");
        awaitProcessed(paymentCompleted("SAGA02"));
        assertThat(status("SAGA02")).isEqualTo(BookingStatus.CONFIRMED);

        Thread.sleep(4500);   // longer than the timeout: the job only touches PENDING_PAYMENT bookings
        assertThat(status("SAGA02")).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(cancelledEventsWithReason("SAGA02", BookingCommandService.PAYMENT_TIMEOUT_REASON)).isZero();
    }

    @Test
    void latePaymentOnCancelledBookingPublishesBookingCancelledAgainSoPaymentServiceRefunds() throws Exception {
        saveBooking("SAGA03", "4C");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(status("SAGA03")).isEqualTo(BookingStatus.CANCELLED));

        // the money arrives after the timeout
        awaitProcessed(paymentCompleted("SAGA03"));

        assertThat(status("SAGA03")).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelledEventsWithReason("SAGA03", BookingCommandService.LATE_PAYMENT_REASON)).isEqualTo(1);
    }

    // ------------------------------------------------------------ helpers

    private void saveBooking(String ref, String seat) {
        Passenger p = passengers.save(
                new Passenger("Anna", "Jensen", ref.toLowerCase() + "@example.com", "P1234567", null));
        bookings.save(new Booking(ref, p, 201L, "SK1501", OffsetDateTime.now().plusDays(1), "A12", "SCHEDULED",
                seat, new BigDecimal("899.00"), "DKK"));
    }

    private BookingStatus status(String ref) {
        return bookings.findByBookingReference(ref).orElseThrow().getStatus();
    }

    private long cancelledEventsWithReason(String ref, String reason) {
        return outbox.findAll().stream()
                .filter(e -> e.getEventType().equals("booking.cancelled"))
                .map(e -> {
                    try {
                        return objectMapper.readValue(e.getPayload(), EventEnvelope.class).payload();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .filter(p -> ref.equals(p.path("bookingReference").asText())
                        && reason.equals(p.path("reason").asText()))
                .count();
    }

    private String paymentCompleted(String ref) throws Exception {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope env = new EventEnvelope(eventId, "payment.completed", OffsetDateTime.now(), "payment-service",
                objectMapper.valueToTree(Map.of("paymentId", 1, "bookingReference", ref,
                        "amount", 899.00, "currency", "DKK", "cardLast4", "4242")));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("airport.events", "payment.completed",
                new Message(objectMapper.writeValueAsBytes(env), props));
        return eventId;
    }

    private void awaitProcessed(String eventId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> processedEvents.existsById(eventId));
    }
}
