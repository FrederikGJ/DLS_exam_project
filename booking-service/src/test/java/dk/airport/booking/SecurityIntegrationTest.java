package dk.airport.booking;

import dk.airport.booking.service.FlightClient;
import dk.airport.booking.service.FlightSeatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Security rules of the booking API (dev plan DP-04): reads by reference are public, booking mutations need a
 * PASSENGER or OPERATIONS token, and a passenger may only list bookings for the e-mail in their own token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import(TestTokens.class)
@Testcontainers
class SecurityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String CREATE = """
            mutation($seat: String!, $email: String!) {
              createBooking(flightId: 1, seatNumber: $seat, passenger: {
                firstName: "Anna", lastName: "Andersen", email: $email, passportNumber: "P1234567" }) {
                bookingReference status
              }
            }""";
    static final String BY_PASSENGER =
            "query($email: String!) { bookingsByPassenger(email: $email) { bookingReference } }";

    @Autowired HttpGraphQlTester anonymous;
    @Autowired TestRestTemplate rest;
    @Value("${spring.graphql.path}") String graphqlPath;
    @MockitoBean FlightClient flightClient;
    GraphQlTester asPassenger;
    GraphQlTester asOperations;

    @BeforeEach
    void clients() {
        asPassenger = anonymous.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build();
        asOperations = anonymous.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build();
        when(flightClient.fetchFlightSeat(anyLong(), any())).thenAnswer(inv -> new FlightSeatInfo(
                inv.getArgument(0), "SK1501", OffsetDateTime.parse("2026-12-24T14:00:00Z"), "A12", "SCHEDULED",
                "DKK", inv.getArgument(1), "ECONOMY", true, new BigDecimal("899.00")));
    }

    @Test
    void publicQueriesAndReadinessNeedNoToken() {
        anonymous.document("{ bookingByReference(reference: \"ZZZZZZ\") { id } }").execute()
                .path("bookingByReference").valueIsNull();
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void mutationWithoutTokenIsUnauthorized() {
        expectCode(anonymous.document(CREATE).variable("seat", "1A").variable("email", "anna@example.com").execute(),
                "UNAUTHORIZED");
        expectCode(anonymous.document("mutation { checkIn(reference: \"ZZZZZZ\") { id } }").execute(), "UNAUTHORIZED");
        expectCode(anonymous.document(BY_PASSENGER).variable("email", "anna@example.com").execute(), "UNAUTHORIZED");
    }

    @Test
    void passengerBooksAndSeesOnlyOwnBookings() {
        String reference = asPassenger.document(CREATE).variable("seat", "2B").variable("email", "anna@example.com")
                .execute()
                .path("createBooking.status").entity(String.class).isEqualTo("PENDING_PAYMENT")
                .path("createBooking.bookingReference").entity(String.class).get();

        // own e-mail (case-insensitive) -> listed
        asPassenger.document(BY_PASSENGER).variable("email", "Anna@Example.com").execute()
                .path("bookingsByPassenger[*].bookingReference").entityList(String.class).contains(reference);
        // somebody else's e-mail -> FORBIDDEN
        expectCode(asPassenger.document(BY_PASSENGER).variable("email", "bo@example.com").execute(), "FORBIDDEN");
        // OPERATIONS may list anyone's
        asOperations.document(BY_PASSENGER).variable("email", "anna@example.com").execute()
                .path("bookingsByPassenger[*].bookingReference").entityList(String.class).contains(reference);
        // the anonymous read by reference stays public
        anonymous.document("query($ref: String!) { bookingByReference(reference: $ref) { status } }")
                .variable("ref", reference).execute()
                .path("bookingByReference.status").entity(String.class).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void operationsMayBookAndCancelToo() {
        String reference = asOperations.document(CREATE).variable("seat", "3C").variable("email", "ops@example.com")
                .execute().path("createBooking.bookingReference").entity(String.class).get();
        asOperations.document("mutation($ref: String!) { cancelBooking(reference: $ref) { status } }")
                .variable("ref", reference).execute()
                .path("cancelBooking.status").entity(String.class).isEqualTo("CANCELLED");
    }

    @Test
    void expiredOrForeignTokenIsRejectedBeforeGraphQl() {
        String expired = TestTokens.token("anna", "anna@example.com", TestTokens.ISSUER, -3600, "PASSENGER");
        String foreign = TestTokens.token("anna", "anna@example.com", "http://evil.example/realms/x", 3600,
                "PASSENGER");
        for (String token : List.of(expired, foreign, "not.a.jwt")) {
            ResponseEntity<String> response = post(token, "{ bookingByReference(reference: \"ZZZZZZ\") { id } }");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        }
    }

    @Test
    void corsPreflightFromTheFrontendOriginIsAllowed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:8080");
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(List.of("authorization", "content-type"));
        ResponseEntity<Void> response = rest.exchange(graphqlPath, HttpMethod.OPTIONS, new HttpEntity<>(headers),
                Void.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:8080");
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<String> post(String rawToken, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(rawToken);
        return rest.postForEntity(graphqlPath, new HttpEntity<>(Map.of("query", query), headers), String.class);
    }

    private static void expectCode(GraphQlTester.Response response, String code) {
        response.errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getExtensions()).containsEntry("code", code);
        });
    }
}
