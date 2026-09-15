package dk.airport.baggage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.baggage.messaging.EventEnvelope;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Security rules of the baggage API (dev plan DP-04): registerBaggage and baggageByBooking need a PASSENGER or
 * OPERATIONS token, updateBaggageStatus needs OPERATIONS, baggage(tagNumber) stays public.
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

    static final String REF = "SEC001";
    static final String REGISTER = """
            mutation($ref: String!) {
              registerBaggage(bookingReference: $ref, weightKg: 12.5, type: CHECKED) { tagNumber status }
            }""";
    static final String UPDATE = "mutation($tag: String!) { updateBaggageStatus(tagNumber: $tag, status: LOADED, "
            + "location: \"Belt 1\") { status lastLocation } }";
    static final String BY_BOOKING = "query($ref: String!) { baggageByBooking(reference: $ref) { tagNumber } }";

    @Autowired HttpGraphQlTester anonymous;
    @Autowired TestRestTemplate rest;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired BookingSnapshotRepository snapshotRepository;
    @Value("${spring.graphql.path}") String graphqlPath;
    GraphQlTester asPassenger;
    GraphQlTester asOperations;

    @BeforeEach
    void clients() {
        asPassenger = anonymous.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build();
        asOperations = anonymous.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build();
    }

    @Test
    void publicQueryAndReadinessNeedNoToken() {
        anonymous.document("{ baggage(tagNumber: \"BAG-UNKNOWN1\") { id } }").execute().path("baggage").valueIsNull();
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void protectedOperationsWithoutTokenAreUnauthorized() {
        expectCode(anonymous.document(REGISTER).variable("ref", REF).execute(), "UNAUTHORIZED");
        expectCode(anonymous.document(UPDATE).variable("tag", "BAG-NOPE0000").execute(), "UNAUTHORIZED");
        expectCode(anonymous.document(BY_BOOKING).variable("ref", REF).execute(), "UNAUTHORIZED");
    }

    @Test
    void passengerRegistersButOnlyOperationsUpdatesStatus() {
        // a confirmed booking must be known first (booking.confirmed event from booking-service)
        publish("booking.confirmed", Map.of(
                "bookingId", 1, "bookingReference", REF, "flightId", 1, "flightNumber", "SK1501",
                "departureTime", "2026-12-24T14:00:00Z", "seatNumber", "12C", "price", 899.00, "currency", "DKK",
                "status", "CONFIRMED",
                "passenger", Map.of("firstName", "Anna", "lastName", "Andersen", "email", "anna@example.com")));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(snapshotRepository.findById(REF)).isPresent());

        String tag = asPassenger.document(REGISTER).variable("ref", REF).execute()
                .path("registerBaggage.status").entity(String.class).isEqualTo("REGISTERED")
                .path("registerBaggage.tagNumber").entity(String.class).get();

        expectCode(asPassenger.document(UPDATE).variable("tag", tag).execute(), "FORBIDDEN");

        asOperations.document(UPDATE).variable("tag", tag).execute()
                .path("updateBaggageStatus.status").entity(String.class).isEqualTo("LOADED")
                .path("updateBaggageStatus.lastLocation").entity(String.class).isEqualTo("Belt 1");

        asPassenger.document(BY_BOOKING).variable("ref", REF).execute()
                .path("baggageByBooking[*].tagNumber").entityList(String.class).containsExactly(tag);
        anonymous.document("query($tag: String!) { baggage(tagNumber: $tag) { status } }").variable("tag", tag)
                .execute().path("baggage.status").entity(String.class).isEqualTo("LOADED");
    }

    @Test
    void expiredOrForeignTokenIsRejectedBeforeGraphQl() {
        String expired = TestTokens.token("anna", "anna@example.com", TestTokens.ISSUER, -3600, "PASSENGER");
        String foreign = TestTokens.token("anna", "anna@example.com", "http://evil.example/realms/x", 3600,
                "PASSENGER");
        for (String token : List.of(expired, foreign, "not.a.jwt")) {
            ResponseEntity<String> response = post(token, "{ baggage(tagNumber: \"BAG-UNKNOWN1\") { id } }");
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

    private void publish(String type, Map<String, Object> payload) {
        try {
            EventEnvelope env = new EventEnvelope(UUID.randomUUID().toString(), type, OffsetDateTime.now(),
                    "booking-service", objectMapper.valueToTree(payload));
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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
