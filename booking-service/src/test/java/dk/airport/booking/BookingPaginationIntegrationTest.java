package dk.airport.booking;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.Passenger;
import dk.airport.booking.repository.BookingRepository;
import dk.airport.booking.repository.PassengerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Ticket 17.1: limit/offset on bookingsByPassenger (newest first). */
@SpringBootTest
@AutoConfigureGraphQlTester
@Testcontainers
class BookingPaginationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired GraphQlTester graphQlTester;
    @Autowired BookingRepository bookings;
    @Autowired PassengerRepository passengers;

    @Test
    void bookingsByPassengerIsPagedNewestFirst() throws Exception {
        Passenger p = passengers.save(new Passenger("Anna", "Jensen", "paging@example.com", "P1234567", null));
        for (String ref : List.of("PAGE01", "PAGE02", "PAGE03")) {
            bookings.save(new Booking(ref, p, 301L, "SK1501", OffsetDateTime.now().plusDays(1), "A12", "SCHEDULED",
                    "1" + ref.charAt(5) + "A", new BigDecimal("899.00"), "DKK"));
            Thread.sleep(5);   // distinct created_at
        }

        assertThat(refs("{ bookingsByPassenger(email: \"paging@example.com\", limit: 2) { bookingReference } }"))
                .containsExactly("PAGE03", "PAGE02");
        assertThat(refs("{ bookingsByPassenger(email: \"paging@example.com\", limit: 2, offset: 2) { bookingReference } }"))
                .containsExactly("PAGE01");
        assertThat(refs("{ bookingsByPassenger(email: \"PAGING@example.com\") { bookingReference } }"))
                .hasSize(3);
    }

    @Test
    void limitAboveHundredIsAValidationError() {
        graphQlTester.document("{ bookingsByPassenger(email: \"x@example.com\", limit: 101) { id } }").execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                });
    }

    private List<String> refs(String query) {
        return graphQlTester.document(query).execute().path("bookingsByPassenger[*].bookingReference")
                .entityList(String.class).get();
    }
}
