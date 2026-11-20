package dk.airport.shop;

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

/** Ticket 17.1: limit/offset on shops (against the Flyway seed data). */
@SpringBootTest
@AutoConfigureGraphQlTester
@Testcontainers
class ShopPaginationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired GraphQlTester graphQlTester;

    @Test
    void pagesAreConsecutiveSlicesOfTheFullList() {
        List<String> all = ids("{ shops(limit: 100) { id } }");
        assertThat(all).hasSizeGreaterThan(4);

        List<String> paged = new ArrayList<>();
        paged.addAll(ids("{ shops(limit: 2, offset: 0) { id } }"));
        paged.addAll(ids("{ shops(limit: 2, offset: 2) { id } }"));
        assertThat(paged).containsExactlyElementsOf(all.subList(0, 4));
    }

    @Test
    void openNowFilterIsAppliedBeforeThePageIsCut() {
        // openNow is evaluated in Java: the page must come from the filtered list, not from the unfiltered rows
        List<String> allOpen = ids("{ shops(filter: { openNow: true }, limit: 100) { id } }");
        List<String> firstTwo = ids("{ shops(filter: { openNow: true }, limit: 2) { id } }");
        assertThat(firstTwo).containsExactlyElementsOf(allOpen.subList(0, Math.min(2, allOpen.size())));
    }

    @Test
    void limitAboveHundredIsAValidationError() {
        graphQlTester.document("{ shops(limit: 101) { id } }").execute().errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
        });
    }

    private List<String> ids(String query) {
        return graphQlTester.document(query).execute().path("shops[*].id").entityList(String.class).get();
    }
}
