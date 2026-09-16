package dk.airport.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * askRoute end to end (dev plan DP-17): GraphQL over HTTP, real PostgreSQL seed data and Dijkstra, and Ollama's
 * {@code POST /api/chat} stubbed with a {@link MockRestServiceServer}, so the build never needs a running model.
 * Also covers {@code RouteService.routeVia} on the seed graph.
 *
 * <p>The stub is wired as a {@code RestClientCustomizer} bean ({@link OllamaStub}): Spring Boot applies it to the
 * auto-configured {@code RestClient.Builder} that {@code OllamaClient} is built from, and the customizer hands out
 * the bound server. ({@code @AutoConfigureMockRestServiceServer} cannot be used here: with RANDOM_PORT the context
 * also builds a TestRestTemplate, and the auto-configured server refuses to serve both a RestTemplate and a
 * RestClient.) The start-up warm-up call ({@code OllamaWarmUp}) is switched off, because the stub would count it as
 * an unexpected request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.ai.warm-up=false")
@AutoConfigureHttpGraphQlTester
@Import({TestTokens.class, AiConciergeIntegrationTest.OllamaStub.class})
@Testcontainers
class AiConciergeIntegrationTest {

    @TestConfiguration
    static class OllamaStub {
        @Bean
        MockServerRestClientCustomizer mockServerRestClientCustomizer() {
            return new MockServerRestClientCustomizer();
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String CHAT_URL = "http://localhost:11434/api/chat";
    static final String ASK = """
            query($q: String!, $from: ID!, $acc: Boolean) {
              askRoute(question: $q, fromNodeId: $from, accessibleOnly: $acc) {
                interpretation aiUsed fallbackReason model
                shop { id name category terminal }
                toNode { id name }
                route {
                  totalDistanceM estimatedMinutes
                  steps { instruction distance node { name floor } }
                  shopsAlongRoute { name }
                }
              }
            }""";

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired MockServerRestClientCustomizer ollamaStub;
    @Autowired ObjectMapper objectMapper;
    /** The stubbed Ollama: expectations on POST /api/chat go here. */
    MockRestServiceServer ollama;

    @BeforeEach
    void bindOllama() {
        ollama = ollamaStub.getServer();
    }

    @AfterEach
    void resetOllama() {
        ollama.reset();
    }

    @Test
    void modelAnswerBecomesARouteViaTheShopToTheDestination() throws Exception {
        long securityT2 = nodeId("Security T2");
        ollama.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("qwen2.5:1.5b"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.options.temperature").value(0))
                .andExpect(jsonPath("$.format.required", contains("english", "need", "shop", "fits")))
                .andExpect(jsonPath("$.format.properties.shop.enum", hasItems("Starbucks", "Apoteket", "SAS Lounge")))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content",
                        containsString("- Starbucks | terminal T2 | FOOD (food and drink) | Kaffe, te og bagværk.")))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content")
                        .value("I am in terminal T2. Hvor finder jeg en kop kaffe på vej til gate B12?"))
                .andRespond(withSuccess(reply("Where can I find a cup of coffee on my way to gate B12?", "coffee",
                        "Starbucks", true), MediaType.APPLICATION_JSON));

        GraphQlTester.Response r = ask("Hvor finder jeg en kop kaffe på vej til gate B12?", securityT2, false);

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(true)
                .path("askRoute.model").entity(String.class).isEqualTo("qwen2.5:1.5b")
                .path("askRoute.fallbackReason").valueIsNull()
                .path("askRoute.interpretation").entity(String.class)
                .isEqualTo("Sprogmodellen forstod \"coffee\": Starbucks på vej til Gate B12")
                .path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks")
                .path("askRoute.toNode.name").entity(String.class).isEqualTo("Gate B12")
                // Security T2 -> Central -> Starbucks (130 m), Starbucks -> Central -> Duty Free -> North -> Pier B
                // -> B12 (340 m): two Dijkstra legs joined by routeVia
                .path("askRoute.route.totalDistanceM").entity(Integer.class).isEqualTo(470)
                .path("askRoute.route.estimatedMinutes").entity(Integer.class).isEqualTo(6);
        List<String> steps = r.path("askRoute.route.steps[*].instruction").entityList(String.class).get();
        assertThat(steps).hasSize(8);
        assertThat(steps.get(0)).isEqualTo("Start ved Security T2");
        assertThat(steps.get(2)).isEqualTo("Stop ved Starbucks T2, fortsæt derefter mod Gate B12");
        assertThat(steps.get(3)).isEqualTo("Gå 60 m til Junction T2 Central");
        assertThat(steps.get(7)).isEqualTo("Du er fremme ved Gate B12");
        r.path("askRoute.route.steps[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names).containsOnlyOnce("Starbucks T2"));
        r.path("askRoute.route.shopsAlongRoute[*].name").entityList(String.class)
                .contains("Starbucks", "Duty Free Copenhagen T2");
        ollama.verify();
    }

    @Test
    void modelFindsTheShopForAQuestionWithoutKnownWords() throws Exception {
        long entranceT1 = nodeId("Entrance T1");
        ollama.expect(requestTo(CHAT_URL)).andRespond(withSuccess(
                reply("Where can I get something for motion sickness?", "medicine", "Apoteket", true),
                MediaType.APPLICATION_JSON));

        ask("Hvor kan jeg få noget mod køresyge?", entranceT1, false)
                .path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(true)
                .path("askRoute.shop.name").entity(String.class).isEqualTo("Apoteket")
                .path("askRoute.route.steps[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names).containsExactly("Entrance T1", "Check-in T1", "Apotek T1"));
        ollama.verify();
    }

    @Test
    void accessibleOnlyReachesTheModelsChoiceWithoutStairs() throws Exception {
        long central = nodeId("Junction T1 Central");
        ollama.expect(requestTo(CHAT_URL)).andRespond(withSuccess(
                reply("I would like to relax in a lounge", "lounge", "SAS Lounge", true), MediaType.APPLICATION_JSON));

        ask("Jeg vil gerne slappe af i en lounge", central, true)
                .path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(true)
                .path("askRoute.shop.name").entity(String.class).isEqualTo("SAS Lounge")
                .path("askRoute.toNode").valueIsNull()
                .path("askRoute.route.totalDistanceM").entity(Integer.class).isEqualTo(225)   // elevator, not stairs
                .path("askRoute.route.steps[*].instruction").entityList(String.class)
                .satisfies(s -> assertThat(s)
                        .anyMatch(i -> i.startsWith("Tag elevatoren"))
                        .noneMatch(i -> i.contains("trappen")));
    }

    @Test
    void ollamaErrorFallsBackToKeywordSearchAndNearestShop() {
        long securityT2 = nodeId("Security T2");
        ollama.expect(requestTo(CHAT_URL))
                .andRespond(withServerError().body("{\"error\":\"model 'qwen2.5:1.5b' not found\"}"));

        ask("Hvor finder jeg en kop kaffe?", securityT2, false)
                .path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false)
                .path("askRoute.model").valueIsNull()
                .path("askRoute.fallbackReason").entity(String.class).satisfies(reason ->
                        assertThat(reason).startsWith("Ollama svarede ikke").contains("500"))
                .path("askRoute.interpretation").entity(String.class).satisfies(i ->
                        assertThat(i).contains("kaffe").contains("Starbucks"))
                // FOOD shops in T2 mentioning kaffe: Starbucks (130 m) beats Lagkagehuset (200 m)
                .path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks")
                .path("askRoute.route.totalDistanceM").entity(Integer.class).isEqualTo(130);
        ollama.verify();
    }

    @Test
    void unreachableOllamaFallsBackAndFindsDestinationInTheQuestion() {
        long securityT2 = nodeId("Security T2");
        ollama.expect(requestTo(CHAT_URL)).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        GraphQlTester.Response r = ask("Jeg vil købe parfume på vej til gate B12", securityT2, false);

        r.path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false)
                .path("askRoute.fallbackReason").entity(String.class).satisfies(reason ->
                        assertThat(reason).contains("Connection refused"))
                .path("askRoute.shop.name").entity(String.class).isEqualTo("Duty Free Copenhagen T2")
                .path("askRoute.toNode.name").entity(String.class).isEqualTo("Gate B12")
                // Security T2 -> Central -> Duty Free T2 (130 m) -> North -> Pier B -> B12 (220 m)
                .path("askRoute.route.totalDistanceM").entity(Integer.class).isEqualTo(350);
        assertThat(r.path("askRoute.route.steps[*].instruction").entityList(String.class).get())
                .hasSize(6).last().asString().isEqualTo("Du er fremme ved Gate B12");
    }

    @Test
    void unknownShopNameIsIgnoredAndMalformedAnswerFallsBack() throws Exception {
        long securityT2 = nodeId("Security T2");
        ollama.expect(requestTo(CHAT_URL)).andRespond(withSuccess(
                reply("coffee", "coffee", "Hamburger Heaven", true), MediaType.APPLICATION_JSON));
        ask("kaffe", securityT2, false)
                .path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(true)
                .path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks");
        ollama.verify();
        ollama.reset();

        ollama.expect(requestTo(CHAT_URL)).andRespond(withSuccess(
                chatResponse("I am sorry, I cannot help with that."), MediaType.APPLICATION_JSON));
        ask("kaffe", securityT2, false)
                .path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(false)
                .path("askRoute.fallbackReason").entity(String.class).satisfies(reason ->
                        assertThat(reason).contains("JSON"))
                .path("askRoute.shop.name").entity(String.class).isEqualTo("Starbucks");
        ollama.verify();
    }

    @Test
    void nothingFitsGivesAnAnswerWithoutShopOrRoute() throws Exception {
        long securityT2 = nodeId("Security T2");
        ollama.expect(requestTo(CHAT_URL)).andRespond(withSuccess(
                reply("Where is the nearest toilet?", "toilet", "Starbucks", false), MediaType.APPLICATION_JSON));

        ask("Hvor er nærmeste toilet?", securityT2, false)
                .path("askRoute.aiUsed").entity(Boolean.class).isEqualTo(true)
                .path("askRoute.fallbackReason").valueIsNull()
                .path("askRoute.shop").valueIsNull()
                .path("askRoute.route").valueIsNull()
                .path("askRoute.interpretation").entity(String.class).satisfies(i ->
                        assertThat(i).contains("toilet").contains("ingen butik"));
    }

    @Test
    void questionIsValidatedAndStartNodeMustExist() {
        long securityT2 = nodeId("Security T2");
        ask("   ", securityT2, false).errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR");
            assertThat(errors.get(0).getMessage()).contains("question");
        });
        ask("a".repeat(501), securityT2, false).errors().satisfy(errors ->
                assertThat(errors.get(0).getExtensions()).containsEntry("code", "VALIDATION_ERROR"));
        ask("kaffe", 999999L, false).errors().satisfy(errors ->
                assertThat(errors.get(0).getExtensions()).containsEntry("code", "NOT_FOUND"));
        ollama.verify();                        // none of the three reached Ollama
    }

    // ------------------------------------------------------------------ helpers

    private GraphQlTester.Response ask(String question, long from, boolean accessibleOnly) {
        return graphQlTester.document(ASK)
                .variable("q", question).variable("from", from).variable("acc", accessibleOnly)
                .execute();
    }

    /** A model answer that follows ConciergePrompt's schema, wrapped in an /api/chat response. */
    private String reply(String english, String need, String shop, boolean fits) throws Exception {
        return chatResponse(objectMapper.writeValueAsString(Map.of(
                "english", english, "need", need, "shop", shop, "fits", fits)));
    }

    /** An /api/chat response as Ollama sends it in non-streaming mode; {@code content} is the model's text. */
    private String chatResponse(String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "model", "qwen2.5:1.5b",
                "created_at", "2026-09-15T12:00:00Z",
                "message", Map.of("role", "assistant", "content", content),
                "done", true,
                "total_duration", 2_500_000_000L,
                "prompt_eval_count", 1200,
                "eval_count", 40));
    }

    private long nodeId(String name) {
        List<Map> nodes = graphQlTester.document("{ navNodes { id name } }").execute()
                .path("navNodes").entityList(Map.class).get();
        return nodes.stream().filter(n -> name.equals(n.get("name"))).map(n -> Long.parseLong(n.get("id").toString()))
                .findFirst().orElseThrow(() -> new AssertionError("no node named " + name));
    }
}
