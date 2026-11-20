package dk.airport.shop;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import dk.airport.shop.domain.Route;
import dk.airport.shop.domain.RouteStep;
import dk.airport.shop.domain.Shop;
import dk.airport.shop.service.RouteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * askRoute end to end: GraphQL → AskRouteService → OllamaClient over real HTTP → Dijkstra on the Flyway seed data.
 * Ollama is a WireMock server, so CI never needs a real model. Real PostgreSQL + RabbitMQ via Testcontainers,
 * like ShopServiceIntegrationTest.
 *
 * <p>{@code @DirtiesContext}: this class has its own containers, so its Spring context is closed right after the class
 * instead of being cached (a cached context would keep polling the outbox against a stopped database at JVM exit).
 * {@code @Testcontainers} is declared first, so JUnit closes the context before it stops the containers.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureGraphQlTester
@DirtiesContext
class AskRouteIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    /**
     * Fake Ollama. Started before the Spring context, so its URL can be used as app.ai.url. Plain-text HTTP/2 is off:
     * the JDK HttpClient offers an h2c upgrade, which Jetty (WireMock) accepts and real Ollama (HTTP/1.1) ignores.
     */
    static final WireMockServer ollama = new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));

    static {
        ollama.start();
    }

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("app.ai.url", ollama::baseUrl);
        registry.add("app.ai.timeout", () -> "1s");          // short, so the timeout test is fast
        registry.add("app.ai.model", () -> "test-model");     // checked in the request body
    }

    @AfterAll
    static void stopOllama() {
        ollama.stop();
    }

    @Autowired GraphQlTester graphQlTester;
    @Autowired RouteService routeService;

    static final String ASK = """
            query($q: String!, $from: ID!, $acc: Boolean = false) {
              askRoute(question: $q, fromNodeId: $from, accessibleOnly: $acc) {
                aiUsed fallbackReason
                shop { name terminal }
                destination { name }
                route { totalDistanceM estimatedMinutes steps { instruction } }
              }
            }""";

    @BeforeEach
    void resetOllama() {
        ollama.resetAll();
    }

    /** Ollama's /api/chat response with the given text as the assistant's message. */
    static void modelAnswers(String content) {
        ollama.stubFor(post("/api/chat").willReturn(okJson(
                "{\"model\":\"qwen2.5:1.5b\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":"
                        + quote(content) + "}}")));
    }

    @Test
    void happyPathRoutesViaTheShopTheModelChose() {
        modelAnswers("{\"need\":\"coffee\",\"shop\":\"Starbucks (T2)\",\"destination\":\"Gate B12\"}");

        GraphQlTester.Response r = ask("Hvor kan jeg købe kaffe på vej til gate B12?", nodeId("Security T2"));

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(true);
        r.path("askRoute.fallbackReason").valueIsNull();
        r.path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks");
        r.path("askRoute.destination.name").entity(String.class).isEqualTo("Gate B12");
        List<String> steps = r.path("askRoute.route.steps[*].instruction").entityList(String.class).get();
        assertThat(steps.get(0)).isEqualTo("Start ved Security T2");
        assertThat(steps).contains("Stop ved Starbucks T2");
        assertThat(steps.get(steps.size() - 1)).isEqualTo("Du er fremme ved Gate B12");

        // The request followed the prompt contract: JSON schema with the closed list, no streaming
        ollama.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.options.temperature", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.format.properties.shop.enum[?(@ == 'Starbucks (T2)')]"))
                .withRequestBody(matchingJsonPath("$.format.properties.destination.enum[?(@ == 'Gate B12')]"))
                .withRequestBody(matchingJsonPath("$.messages[1].content", containing("gate B12"))));
    }

    @Test
    void invalidJsonFallsBackToKeywords() {
        modelAnswers("Gå til Starbucks!");

        GraphQlTester.Response r = ask("kaffe på vej til gate B12", nodeId("Security T2"));

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false);
        r.path("askRoute.fallbackReason").entity(String.class).satisfies(reason -> assertThat(reason).contains("JSON"));
        r.path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks");
        r.path("askRoute.destination.name").entity(String.class).isEqualTo("Gate B12");
    }

    @Test
    void timeoutFallsBackToKeywords() {
        ollama.stubFor(post("/api/chat").willReturn(okJson("{}").withFixedDelay(3000)));   // > the 1 s timeout

        GraphQlTester.Response r = ask("kaffe", nodeId("Security T2"));

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false);
        r.path("askRoute.fallbackReason").entity(String.class).satisfies(reason -> assertThat(reason).startsWith("AI utilgængelig"));
        r.path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks");
    }

    @Test
    void ollamaDownFallsBackToKeywords() {
        ollama.stubFor(post("/api/chat").willReturn(aResponse().withStatus(500)));

        GraphQlTester.Response r = ask("I need a pharmacy", nodeId("Security T1"));

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false);
        r.path("askRoute.fallbackReason").entity(String.class).satisfies(reason -> assertThat(reason).startsWith("AI utilgængelig"));
        r.path("askRoute.shop.name").entity(String.class).isEqualTo("Apoteket");
    }

    @Test
    void shopThatDoesNotExistIsRejected() {
        modelAnswers("{\"need\":\"burger\",\"shop\":\"Burger King (T2)\",\"destination\":\"NONE\"}");

        GraphQlTester.Response r = ask("kaffe", nodeId("Security T2"));

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false);
        r.path("askRoute.fallbackReason").entity(String.class).satisfies(reason -> assertThat(reason).contains("ukendt butik"));
        r.path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks");   // the fallback's choice
    }

    @Test
    void emptyAnswerFromOllamaFallsBackToKeywords() {
        modelAnswers("");

        GraphQlTester.Response r = ask("kaffe", nodeId("Security T2"));

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false);
        r.path("askRoute.fallbackReason").entity(String.class).satisfies(reason -> assertThat(reason).contains("empty answer"));
        r.path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks");
    }

    @Test
    void accessibleOnlyTakesTheElevatorToTheLounge() {
        modelAnswers("{\"need\":\"lounge\",\"shop\":\"SAS Lounge (T1)\",\"destination\":\"NONE\"}");
        long from = nodeId("Junction T1 Central");

        GraphQlTester.Response stairs = ask("lounge", from, false);
        GraphQlTester.Response elevator = ask("lounge", from, true);

        // same distances as the plain route in ShopServiceIntegrationTest.accessibleOnlyAvoidsStairs
        stairs.path("askRoute.route.totalDistanceM").entity(Integer.class).isEqualTo(205);
        elevator.path("askRoute.route.totalDistanceM").entity(Integer.class).isEqualTo(225);
        assertThat(elevator.path("askRoute.route.steps[*].instruction").entityList(String.class).get())
                .anyMatch(i -> i.startsWith("Tag elevatoren"))
                .noneMatch(i -> i.contains("trappen"));
    }

    @Test
    void blankOrTooLongQuestionIsAValidationErrorAndOllamaIsNotCalled() {
        long from = nodeId("Security T2");
        for (String question : List.of("   ", "x".repeat(301))) {
            graphQlTester.document(ASK).variable("q", question).variable("from", from).execute()
                    .errors().satisfy(errors -> assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR"));
        }
        assertThat(ollama.getAllServeEvents()).isEmpty();
    }

    @Test
    void routeViaIsTheTwoLegsJoinedAtTheShop() {
        long from = nodeId("Security T2"), shop = nodeId("Starbucks T2"), to = nodeId("Gate B12");
        Route toShop = routeService.route(from, shop, false);
        Route toGate = routeService.route(shop, to, false);

        Route via = routeService.routeVia(from, shop, to, false);

        int total = toShop.totalDistanceM() + toGate.totalDistanceM();
        assertThat(via.totalDistanceM()).isEqualTo(total);
        assertThat(via.estimatedMinutes()).isEqualTo((int) Math.ceil(total / RouteService.METRES_PER_MINUTE));
        // the shop step appears once (the second leg's "Start ved" is dropped) and says "Stop ved"
        List<String> steps = via.steps().stream().map(RouteStep::instruction).toList();
        assertThat(steps).hasSize(toShop.steps().size() + toGate.steps().size() - 1);
        assertThat(steps.get(toShop.steps().size() - 1)).isEqualTo("Stop ved Starbucks T2");
        assertThat(steps.get(0)).isEqualTo("Start ved Security T2");
        assertThat(steps.get(steps.size() - 1)).isEqualTo("Du er fremme ved Gate B12");
        // shops along both legs, each only once
        assertThat(via.shopsAlongRoute()).extracting(Shop::getId).doesNotHaveDuplicates();
    }

    @Test
    void routeViaWithoutADestinationEndsAtTheShop() {
        long from = nodeId("Security T2"), shop = nodeId("Starbucks T2");

        Route via = routeService.routeVia(from, shop, null, false);

        Route direct = routeService.route(from, shop, false);
        assertThat(via.totalDistanceM()).isEqualTo(direct.totalDistanceM());
        assertThat(via.steps()).extracting(RouteStep::instruction)
                .isEqualTo(direct.steps().stream().map(RouteStep::instruction).toList())
                .last().isEqualTo("Du er fremme ved Starbucks T2");
    }

    @Test
    void unknownStartNodeIsNotFound() {
        modelAnswers("{\"need\":\"coffee\",\"shop\":\"Starbucks (T2)\",\"destination\":\"NONE\"}");

        graphQlTester.document(ASK).variable("q", "kaffe").variable("from", 999999).execute()
                .errors().satisfy(errors -> assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND"));
        assertThat(ollama.getAllServeEvents()).isEmpty();             // checked before the LLM is asked
    }

    private GraphQlTester.Response ask(String question, long from) {
        return ask(question, from, false);
    }

    private GraphQlTester.Response ask(String question, long from, boolean accessibleOnly) {
        return graphQlTester.document(ASK).variable("q", question).variable("from", from).variable("acc", accessibleOnly).execute();
    }

    private long nodeId(String name) {
        List<Map> nodes = graphQlTester.document("{ navNodes { id name } }").execute()
                .path("navNodes").entityList(Map.class).get();
        return nodes.stream().filter(n -> name.equals(n.get("name"))).map(n -> Long.parseLong(n.get("id").toString()))
                .findFirst().orElseThrow(() -> new AssertionError("no node named " + name));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
