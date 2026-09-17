package dk.airport.shop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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
 * Security rules of the shop API (dev plan DP-04): every query (shops, search, navigation) is public, the shop
 * mutations need OPERATIONS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@AutoConfigureObservability   // real Prometheus registry and tracing, as in production (off by default in tests)
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
            mutation {
              createShop(input: { name: "Security Test Kiosk", category: RETAIL, terminal: "T1", zone: "Pier A",
                                  floor: 0, openingHours: "08:00-20:00" }) { id name }
            }""";
    static final String DELETE = "mutation($id: ID!) { deleteShop(id: $id) }";

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
        anonymous.document("{ shops { id name } }").execute()
                .path("shops").entityList(Object.class).hasSizeGreaterThan(0);
        anonymous.document("{ searchShops(text: \"duty\") { id } }").execute()
                .path("searchShops").entityList(Object.class).hasSizeGreaterThan(0);
        anonymous.document("{ navNodes { id } }").execute()
                .path("navNodes").entityList(Object.class).hasSizeGreaterThan(0);
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void mutationWithoutTokenIsUnauthorized() {
        expectCode(anonymous.document(CREATE).execute(), "UNAUTHORIZED");
        expectCode(anonymous.document(DELETE).variable("id", 1).execute(), "UNAUTHORIZED");
    }

    @Test
    void passengerMayNotMaintainShops() {
        expectCode(asPassenger.document(CREATE).execute(), "FORBIDDEN");
        expectCode(asPassenger.document(DELETE).variable("id", 1).execute(), "FORBIDDEN");
    }

    @Test
    void operationsCreatesAndDeletesShops() {
        Long id = asOperations.document(CREATE).execute()
                .path("createShop.name").entity(String.class).isEqualTo("Security Test Kiosk")
                .path("createShop.id").entity(Long.class).get();
        expectCode(asPassenger.document(DELETE).variable("id", id).execute(), "FORBIDDEN");
        asOperations.document(DELETE).variable("id", id).execute()
                .path("deleteShop").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void expiredOrForeignTokenIsRejectedBeforeGraphQl() {
        String expired = TestTokens.token("anna", "anna@example.com", TestTokens.ISSUER, -3600, "PASSENGER");
        String foreign = TestTokens.token("anna", "anna@example.com", "http://evil.example/realms/x", 3600,
                "PASSENGER");
        for (String token : List.of(expired, foreign, "not.a.jwt")) {
            ResponseEntity<String> response = post(token, "{ shops { id } }");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        }
    }

    /**
     * Dev plan DP-33: Prometheus scrapes /actuator/prometheus without a token; the other actuator endpoints stay
     * protected.
     */
    @Test
    void prometheusEndpointIsPublicButMetricsNeedOperations() {
        ResponseEntity<String> scrape = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody()).contains("outbox_pending{").contains("application=\"shop-service\"");
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
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
