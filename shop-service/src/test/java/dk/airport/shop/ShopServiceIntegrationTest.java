package dk.airport.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.shop.messaging.EventEnvelope;
import dk.airport.shop.messaging.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.GraphQlTester;
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
 * End-to-end test against real PostgreSQL + RabbitMQ (Testcontainers):
 * Flyway seed, shop queries/mutations, Dijkstra routing via GraphQL and idempotent event consumption.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@Testcontainers
class ShopServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired GraphQlTester graphQlTester;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProcessedEventRepository processedEventRepository;

    @Test
    void seedDataIsLoadedByFlyway() {
        graphQlTester.document("{ shops { id name category terminal openNow node { id name } } }")
                .execute()
                .path("shops").entityList(Object.class).hasSizeGreaterThan(14);

        graphQlTester.document("{ navNodes { id name terminal floor x y type } }")
                .execute()
                .path("navNodes").entityList(Object.class).hasSizeGreaterThan(29);

        graphQlTester.document("{ navNodes(terminal: \"T2\", floor: 1) { name terminal floor } }")
                .execute()
                .path("navNodes[*].terminal").entityList(String.class).containsExactly("T2", "T2", "T2")
                .path("navNodes[*].floor").entityList(Integer.class).contains(1);

        graphQlTester.document("{ navEdges(terminal: \"T1\") { id from { name } to { name } distanceM accessible } }")
                .execute()
                .path("navEdges").entityList(Object.class).hasSizeGreaterThan(10);
    }

    @Test
    void routeFromSecurityToGateIsStepByStep() {
        long from = nodeId("Security T2");
        long to = nodeId("Gate B12");

        GraphQlTester.Response response = graphQlTester.document("""
                query($from: ID!, $to: ID!) {
                  route(fromNodeId: $from, toNodeId: $to) {
                    totalDistanceM estimatedMinutes
                    steps { instruction distance node { name type } }
                    shopsAlongRoute { name }
                  }
                }""")
                .variable("from", from).variable("to", to)
                .execute();

        List<String> instructions = response.path("route.steps[*].instruction").entityList(String.class).get();
        assertThat(instructions).hasSizeGreaterThan(1);
        assertThat(instructions.get(0)).startsWith("Start ved Security T2");
        assertThat(instructions.get(instructions.size() - 1)).startsWith("Du er fremme ved Gate B12");
        assertThat(instructions.subList(1, instructions.size() - 1)).allMatch(i -> i.startsWith("Gå "));

        Integer total = response.path("route.totalDistanceM").entity(Integer.class).get();
        assertThat(total).isEqualTo(350);                 // Security -> Central -> Duty Free -> North -> Pier B -> B12
        response.path("route.estimatedMinutes").entity(Integer.class).isEqualTo(5);
        response.path("route.steps[0].distance").entity(Integer.class).isEqualTo(0);
        response.path("route.shopsAlongRoute[*].name").entityList(String.class).contains("Duty Free Copenhagen T2");
    }

    @Test
    void accessibleOnlyAvoidsStairs() {
        long from = nodeId("Junction T1 Central");
        long to = nodeId("SAS Lounge");

        GraphQlTester.Response stairs = routeQuery(from, to, false);
        GraphQlTester.Response elevator = routeQuery(from, to, true);

        int stairsDistance = stairs.path("route.totalDistanceM").entity(Integer.class).get();
        int elevatorDistance = elevator.path("route.totalDistanceM").entity(Integer.class).get();
        assertThat(stairsDistance).isEqualTo(205);        // Central -> South (120) -> stairs (25) -> lounge (60)
        assertThat(elevatorDistance).isEqualTo(225);      // Central -> Security (70) -> Elevator (80) -> lift (15) -> lounge (60)
        assertThat(elevatorDistance).isGreaterThan(stairsDistance);

        assertThat(stairs.path("route.steps[*].instruction").entityList(String.class).get())
                .anyMatch(i -> i.startsWith("Tag trappen til etage 1"));
        assertThat(elevator.path("route.steps[*].instruction").entityList(String.class).get())
                .anyMatch(i -> i.startsWith("Tag elevatoren til etage 1"))
                .noneMatch(i -> i.contains("trappen"));
    }

    @Test
    void terminalsAreConnectedAndSameNodeRouteIsTrivial() {
        long from = nodeId("Gate A3");
        long to = nodeId("Gate C21");
        routeQuery(from, to, true).path("route.totalDistanceM").entity(Integer.class).satisfies(d -> assertThat(d).isGreaterThan(500));

        routeQuery(from, from, false)
                .path("route.steps").entityList(Object.class).hasSize(1)
                .path("route.totalDistanceM").entity(Integer.class).isEqualTo(0);
    }

    @Test
    void unknownNodeIsNotFound() {
        graphQlTester.document("{ route(fromNodeId: 1, toNodeId: 999999) { totalDistanceM } }")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND");
                });
    }

    @Test
    void searchAndFilterShops() {
        graphQlTester.document("{ searchShops(text: \"duty\") { name category } }")
                .execute()
                .path("searchShops").entityList(Object.class).hasSizeGreaterThan(0)
                .path("searchShops[*].category").entityList(String.class).satisfies(c -> assertThat(c).containsOnly("DUTY_FREE"));

        graphQlTester.document("{ shops(filter: { terminal: \"t1\", category: FOOD }) { name terminal category } }")
                .execute()
                .path("shops[*].terminal").entityList(String.class).satisfies(t -> assertThat(t).isNotEmpty().containsOnly("T1"))
                .path("shops[*].category").entityList(String.class).satisfies(c -> assertThat(c).containsOnly("FOOD"));

        // 7-Eleven is 24/7 -> always in the openNow list
        graphQlTester.document("{ shops(filter: { openNow: true }) { name openNow } }")
                .execute()
                .path("shops[*].name").entityList(String.class).contains("7-Eleven")
                .path("shops[*].openNow").entityList(Boolean.class).satisfies(o -> assertThat(o).containsOnly(true));
    }

    @Test
    void createReadDeleteShop() {
        long nodeId = nodeId("Junction T1 North");
        Long shopId = graphQlTester.document("""
                mutation($nodeId: ID!) {
                  createShop(input: { name: "Test Kiosk", category: RETAIL, terminal: "T1", zone: "Pier A",
                                      floor: 0, openingHours: "08:00-20:00", description: "test", nodeId: $nodeId }) {
                    id name node { name }
                  }
                }""")
                .variable("nodeId", nodeId)
                .execute()
                .path("createShop.node.name").entity(String.class).isEqualTo("Junction T1 North")
                .path("createShop.id").entity(Long.class).get();

        graphQlTester.document("query($id: ID!) { shop(id: $id) { id name } }")
                .variable("id", shopId).execute()
                .path("shop.name").entity(String.class).isEqualTo("Test Kiosk");

        graphQlTester.document("mutation($id: ID!) { updateShop(id: $id, input: { name: \"Test Kiosk 2\", category: FOOD, terminal: \"T1\", zone: \"Pier A\", floor: 0, openingHours: \"24/7\" }) { name category node { id } } }")
                .variable("id", shopId).execute()
                .path("updateShop.name").entity(String.class).isEqualTo("Test Kiosk 2")
                .path("updateShop.node").valueIsNull();

        graphQlTester.document("mutation($id: ID!) { deleteShop(id: $id) }")
                .variable("id", shopId).execute()
                .path("deleteShop").entity(Boolean.class).isEqualTo(true);

        graphQlTester.document("query($id: ID!) { shop(id: $id) { id } }")
                .variable("id", shopId).execute()
                .path("shop").valueIsNull();

        graphQlTester.document("mutation($id: ID!) { deleteShop(id: $id) }")
                .variable("id", shopId).execute()
                .errors().satisfy(errors -> assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND"));
    }

    @Test
    void validationErrorsAreReportedWithCode() {
        graphQlTester.document("mutation { createShop(input: { name: \"\", category: RETAIL, terminal: \"T1\", zone: \"Z\", floor: 0, openingHours: \"8-20\" }) { id } }")
                .execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
                    assertThat(errors.get(0).getMessage()).contains("name").contains("openingHours");
                });
    }

    @Test
    void gateChangedEventIsConsumedOnce() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("flightId", 7, "flightNumber", "DY1050", "oldGate", "B15", "newGate", "B17");
        publish(eventId, "flight.gate.changed", payload);
        publish(eventId, "flight.gate.changed", payload);
        publish(UUID.randomUUID().toString(), "flight.status.changed", Map.of("flightId", 7, "newStatus", "DELAYED"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(processedEventRepository.count()).isEqualTo(2));
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(processedEventRepository.findById(eventId).orElseThrow().getEventType()).isEqualTo("flight.gate.changed");
    }

    // ------------------------------------------------------------------ helpers

    private long nodeId(String name) {
        List<Map> nodes = graphQlTester.document("{ navNodes { id name } }").execute()
                .path("navNodes").entityList(Map.class).get();
        return nodes.stream().filter(n -> name.equals(n.get("name"))).map(n -> Long.parseLong(n.get("id").toString()))
                .findFirst().orElseThrow(() -> new AssertionError("no node named " + name));
    }

    private GraphQlTester.Response routeQuery(long from, long to, boolean accessibleOnly) {
        return graphQlTester.document("""
                query($from: ID!, $to: ID!, $acc: Boolean) {
                  route(fromNodeId: $from, toNodeId: $to, accessibleOnly: $acc) {
                    totalDistanceM estimatedMinutes steps { instruction distance node { name floor } }
                  }
                }""")
                .variable("from", from).variable("to", to).variable("acc", accessibleOnly)
                .execute();
    }

    private void publish(String eventId, String type, Map<String, Object> payload) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, type, OffsetDateTime.now(), "flight-service", objectMapper.valueToTree(payload));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("airport.events", type, new Message(objectMapper.writeValueAsBytes(env), props));
    }
}
