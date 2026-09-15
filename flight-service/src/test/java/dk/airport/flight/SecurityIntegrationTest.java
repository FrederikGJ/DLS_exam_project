package dk.airport.flight;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security rules of the GraphQL API (dev plan DP-04), end to end through the HTTP filter chain with tokens from
 * {@link TestTokens}: public reads and health need no token, mutations without a token give UNAUTHORIZED, the
 * wrong role gives FORBIDDEN, the right role passes, and a bad token is rejected with HTTP 401 before GraphQL.
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

    static final String UPDATE_STATUS =
            "mutation($id: ID!) { updateFlightStatus(flightId: $id, status: DELAYED) { id status } }";

    @Autowired HttpGraphQlTester anonymous;
    @Autowired TestRestTemplate rest;
    @Value("${spring.graphql.path}") String graphqlPath;
    GraphQlTester asPassenger;
    GraphQlTester asOperations;

    @BeforeEach
    void clients() {
        asPassenger = anonymous.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()).build();
        asOperations = anonymous.mutate().header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build();
    }

    @Test
    void publicQueriesAndReadinessNeedNoToken() {
        anonymous.document("{ flights { id flightNumber status } }").execute()
                .path("flights").entityList(Object.class).hasSizeGreaterThan(0);
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void mutationWithoutTokenIsUnauthorized() {
        expectCode(anonymous.document("mutation { updateGate(flightId: 1, gate: \"A1\") { id } }").execute(),
                "UNAUTHORIZED");
        expectCode(anonymous.document(UPDATE_STATUS).variable("id", 1).execute(), "UNAUTHORIZED");
    }

    @Test
    void passengerMayNotChangeFlightStatus() {
        expectCode(asPassenger.document(UPDATE_STATUS).variable("id", scheduledFlightId()).execute(), "FORBIDDEN");
    }

    @Test
    void operationsMayChangeFlightStatus() {
        asOperations.document(UPDATE_STATUS).variable("id", scheduledFlightId()).execute()
                .path("updateFlightStatus.status").entity(String.class).isEqualTo("DELAYED");
    }

    @Test
    void expiredOrForeignTokenIsRejectedBeforeGraphQl() {
        String expired = TestTokens.token("anna", "anna@example.com", TestTokens.ISSUER, -3600, "PASSENGER");
        String foreign = TestTokens.token("anna", "anna@example.com", "http://evil.example/realms/x", 3600,
                "PASSENGER");
        for (String token : List.of(expired, foreign, "not.a.jwt")) {
            ResponseEntity<String> response = post(token, "{ flights { id } }");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        }
    }

    @Test
    void otherActuatorEndpointsRequireOperations() {
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, TestTokens.operations());
        assertThat(rest.exchange("/actuator/metrics", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private Long scheduledFlightId() {
        return anonymous.document("{ flights(filter: { status: SCHEDULED }) { id } }").execute()
                .path("flights[0].id").entity(Long.class).get();
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
