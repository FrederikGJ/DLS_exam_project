package dk.airport.flight;

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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Ticket 17.1: limit/offset on flights (against the Flyway seed data). */
@SpringBootTest
@AutoConfigureGraphQlTester
@Testcontainers
class FlightPaginationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired GraphQlTester graphQlTester;

    @Test
    void pagesAreConsecutiveSlicesOfTheFullList() {
        List<String> all = ids("{ flights(limit: 100) { id } }");
        assertThat(all).hasSizeGreaterThan(6);

        List<String> paged = new ArrayList<>();
        paged.addAll(ids("{ flights(limit: 3, offset: 0) { id } }"));
        paged.addAll(ids("{ flights(limit: 3, offset: 3) { id } }"));
        assertThat(paged).containsExactlyElementsOf(all.subList(0, 6));
    }

    @Test
    void withoutArgumentsTheDefaultLimitApplies() {
        assertThat(ids("{ flights { id } }")).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void offsetPastTheEndGivesAnEmptyList() {
        assertThat(ids("{ flights(offset: 10000) { id } }")).isEmpty();
    }

    @Test
    void limitOutsideOneToHundredIsAValidationError() {
        for (String query : List.of("{ flights(limit: 101) { id } }", "{ flights(limit: 0) { id } }",
                "{ flights(offset: -1) { id } }")) {
            graphQlTester.document(query).execute().errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
            });
        }
    }

    private List<String> ids(String query) {
        return graphQlTester.document(query).execute().path("flights[*].id").entityList(String.class).get();
    }
}
