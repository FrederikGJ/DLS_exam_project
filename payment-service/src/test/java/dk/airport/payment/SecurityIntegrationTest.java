package dk.airport.payment;

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
 * Security rules of the payment API (dev plan DP-04): pay and paymentsByBooking need a PASSENGER or OPERATIONS
 * token, refund needs OPERATIONS, payment(id) stays public.
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

    static final String PAY = """
            mutation($ref: String!) {
              pay(bookingReference: $ref, amount: 100.00, cardNumber: "4242424242424242", expiry: "12/30", cvv: "123") {
                id status
              }
            }""";
    static final String REFUND = "mutation($id: ID!) { refund(paymentId: $id) { id status } }";
    static final String HISTORY = "query($ref: String!) { paymentsByBooking(reference: $ref) { id status } }";

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
    void publicQueryAndReadinessNeedNoToken() {
        anonymous.document("{ payment(id: 999999) { id } }").execute().path("payment").valueIsNull();
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void payAndHistoryWithoutTokenAreUnauthorized() {
        expectCode(anonymous.document(PAY).variable("ref", "SEC001").execute(), "UNAUTHORIZED");
        expectCode(anonymous.document(HISTORY).variable("ref", "SEC001").execute(), "UNAUTHORIZED");
        expectCode(anonymous.document(REFUND).variable("id", 1).execute(), "UNAUTHORIZED");
    }

    @Test
    void passengerPaysButOnlyOperationsMayRefund() {
        Long paymentId = asPassenger.document(PAY).variable("ref", "SEC002").execute()
                .path("pay.status").entity(String.class).isEqualTo("COMPLETED")
                .path("pay.id").entity(Long.class).get();

        asPassenger.document(HISTORY).variable("ref", "SEC002").execute()
                .path("paymentsByBooking").entityList(Object.class).hasSize(1);

        expectCode(asPassenger.document(REFUND).variable("id", paymentId).execute(), "FORBIDDEN");

        asOperations.document(REFUND).variable("id", paymentId).execute()
                .path("refund.status").entity(String.class).isEqualTo("REFUNDED");
    }

    @Test
    void expiredOrForeignTokenIsRejectedBeforeGraphQl() {
        String expired = TestTokens.token("anna", "anna@example.com", TestTokens.ISSUER, -3600, "PASSENGER");
        String foreign = TestTokens.token("anna", "anna@example.com", "http://evil.example/realms/x", 3600,
                "PASSENGER");
        for (String token : List.of(expired, foreign, "not.a.jwt")) {
            ResponseEntity<String> response = post(token, "{ payment(id: 1) { id } }");
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
