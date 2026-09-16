package dk.airport.shop.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.shop.domain.ApiException;
import dk.airport.shop.domain.ErrorCode;
import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.NodeType;
import dk.airport.shop.domain.Route;
import dk.airport.shop.domain.Shop;
import dk.airport.shop.domain.ShopCategory;
import dk.airport.shop.repository.NavNodeRepository;
import dk.airport.shop.repository.ShopRepository;
import dk.airport.shop.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * askRoute with a mocked {@link OllamaClient} (dev plan DP-17): the model's need and suggested shop are scored
 * together with the question, the route goes via the chosen shop, and every failure mode (client error, malformed
 * JSON, AI disabled) ends in the keyword fallback instead of an error.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiConciergeServiceTest {

    static final AiProperties ENABLED =
            new AiProperties(true, "http://localhost:11434", "qwen2.5:1.5b", Duration.ofSeconds(15), false);

    @Mock OllamaClient ollama;
    @Mock ShopRepository shops;
    @Mock NavNodeRepository nodes;
    @Mock RouteService routes;

    final NavNode securityT2 = node(24L, "Security T2", "T2", NodeType.SECURITY);
    final NavNode junction = node(25L, "Junction T2 Central", "T2", NodeType.JUNCTION);
    final NavNode gateB12 = node(31L, "Gate B12", "T2", NodeType.GATE);
    final NavNode dutyFreeNode = node(36L, "Duty Free T2", "T2", NodeType.SHOP);
    final NavNode starbucksNode = node(38L, "Starbucks T2", "T2", NodeType.SHOP);
    final NavNode lagkageNode = node(41L, "Lagkagehuset T2", "T2", NodeType.SHOP);
    final NavNode sevenNode = node(15L, "7-Eleven T1", "T1", NodeType.SHOP);

    final Shop sevenEleven = shop(4L, "7-Eleven", ShopCategory.FOOD, "T1",
            "Kiosk med kaffe, snacks og rejseartikler.", sevenNode);
    final Shop dutyFree = shop(9L, "Duty Free Copenhagen T2", ShopCategory.DUTY_FREE, "T2",
            "Tax free butik med parfume, kosmetik, vin og chokolade.", dutyFreeNode);
    final Shop starbucks = shop(10L, "Starbucks", ShopCategory.FOOD, "T2", "Kaffe, te og bagværk.", starbucksNode);
    final Shop lagkagehuset = shop(12L, "Lagkagehuset", ShopCategory.FOOD, "T2",
            "Dansk bageri: kanelsnegle, rugbrød og kaffe.", lagkageNode);

    final Route toStarbucks = new Route(List.of(), 130, 2, List.of());
    final Route toLagkagehuset = new Route(List.of(), 200, 3, List.of());
    final Route toDutyFree = new Route(List.of(), 130, 2, List.of());
    final Route toSevenEleven = new Route(List.of(), 500, 7, List.of());
    final Route viaRoute = new Route(List.of(), 470, 6, List.of());

    AiConciergeService service;

    @BeforeEach
    void setUp() {
        service = new AiConciergeService(ollama, ENABLED, shops, nodes, routes, new ObjectMapper());
        when(ollama.model()).thenReturn("qwen2.5:1.5b");
        when(nodes.findById(24L)).thenReturn(Optional.of(securityT2));
        when(nodes.findAll(any(Sort.class))).thenReturn(List.of(sevenNode, securityT2, junction, gateB12,
                dutyFreeNode, starbucksNode, lagkageNode));
        when(shops.findAll(any(Sort.class))).thenReturn(List.of(sevenEleven, dutyFree, starbucks, lagkagehuset));
        when(routes.route(24L, 38L, false)).thenReturn(toStarbucks);
        when(routes.route(24L, 41L, false)).thenReturn(toLagkagehuset);
        when(routes.route(24L, 36L, false)).thenReturn(toDutyFree);
        when(routes.route(24L, 15L, false)).thenReturn(toSevenEleven);
        when(routes.routeVia(eq(24L), anyLong(), eq(31L), eq(false))).thenReturn(viaRoute);
    }

    @Test
    void modelAnswerIsScoredAndRoutedViaTheShopToTheDestinationInTheQuestion() {
        modelAnswers("Where can I find a cup of coffee on my way to gate B12?", "coffee", "Starbucks", true);

        AiRouteAnswer a = service.askRoute("Hvor finder jeg en kop kaffe på vej til gate B12?", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(starbucks);
        assertThat(a.toNode()).isSameAs(gateB12);
        assertThat(a.route()).isSameAs(viaRoute);
        assertThat(a.interpretation()).isEqualTo("Sprogmodellen forstod \"coffee\": Starbucks på vej til Gate B12");
        assertThat(a.fallbackReason()).isNull();
        assertThat(a.model()).isEqualTo("qwen2.5:1.5b");
        verify(routes).routeVia(24L, 38L, 31L, false);
    }

    @Test
    void promptAndSchemaListEveryShopByName() {
        modelAnswers("coffee now", "coffee", "Starbucks", true);

        service.askRoute("kaffe\n\tnu", 24L, false);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> format = ArgumentCaptor.forClass(Map.class);
        verify(ollama).chat(system.capture(), user.capture(), format.capture());
        assertThat(system.getValue())
                .contains("- Starbucks | terminal T2 | FOOD (food and drink) | Kaffe, te og bagværk.")
                .contains("- Duty Free Copenhagen T2 | terminal T2 | DUTY_FREE (tax free) |")
                .contains("\"need\"")
                .doesNotContain("Gate B12");               // destinations are found in the question, not by the model
        assertThat(user.getValue()).isEqualTo("I am in terminal T2. kaffe nu");   // control characters collapsed

        Map<String, Object> schema = format.getValue();
        assertThat(schema).containsEntry("required", List.of("english", "need", "shop", "fits"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(properties.keySet()).containsExactly("english", "need", "shop", "fits");   // the model's order
        assertThat(properties.get("shop").get("enum"))
                .isEqualTo(List.of("7-Eleven", "Duty Free Copenhagen T2", "Starbucks", "Lagkagehuset"));
    }

    @Test
    void modelWordsFindWhatTheKeywordsCannotAndTheSuggestionSettlesTheTie() {
        modelAnswers("I need some caffeine before boarding", "coffee", "Lagkagehuset", true);

        AiRouteAnswer a = service.askRoute("Jeg trænger til koffein inden boarding", 24L, false);

        // "coffee" makes Starbucks and Lagkagehuset equal (FOOD + terminal T2); the suggestion beats the shorter walk
        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(lagkagehuset);
        assertThat(a.route()).isSameAs(toLagkagehuset);

        // without the model, not a single word of the question is known
        when(ollama.chat(anyString(), anyString(), any())).thenThrow(new OllamaClient.OllamaException("timeout"));
        assertThat(service.askRoute("Jeg trænger til koffein inden boarding", 24L, false).shop()).isNull();
    }

    @Test
    void clearKeywordMatchBeatsAWrongSuggestion() {
        modelAnswers("Where can I buy perfume?", "perfume", "Starbucks", true);

        AiRouteAnswer a = service.askRoute("Hvor kan jeg købe parfume?", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(dutyFree);
        assertThat(a.route()).isSameAs(toDutyFree);
    }

    @Test
    void suggestionAloneIsEnoughWhenNoWordMatches() {
        modelAnswers("I forgot my toothbrush", "toothbrush", "7-Eleven", true);

        AiRouteAnswer a = service.askRoute("Jeg har glemt min tandbørste", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(sevenEleven);
        assertThat(a.route()).isSameAs(toSevenEleven);
        assertThat(a.interpretation()).isEqualTo("Sprogmodellen forstod \"toothbrush\": 7-Eleven");
    }

    @Test
    void modelSayingNothingFitsGivesAnAnswerWithoutShopOrRoute() {
        modelAnswers("Where is the nearest toilet?", "toilet", "Starbucks", false);

        AiRouteAnswer a = service.askRoute("Hvor er nærmeste toilet?", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isNull();
        assertThat(a.route()).isNull();
        assertThat(a.fallbackReason()).isNull();
        assertThat(a.interpretation()).contains("\"toilet\"").contains("ingen butik");
        verifyNoInteractions(routes);
    }

    @Test
    void unknownSuggestedShopIsIgnored() {
        modelAnswers("coffee", "coffee", "Hamburger Heaven", true);

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        // Starbucks and Lagkagehuset score the same without a valid suggestion; the nearer one (130 m < 200 m) wins
        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(starbucks);
    }

    @Test
    void codeFenceAndNoneAsNeedAreTolerated() {
        when(ollama.chat(anyString(), anyString(), any())).thenReturn(
                "```json\n{\"english\": \"coffee\", \"need\": \"none\", \"shop\": \"Starbucks\", \"fits\": true}\n```");

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(starbucks);
        assertThat(a.interpretation()).isEqualTo("Sprogmodellen forstod spørgsmålet: Starbucks");
    }

    @Test
    void clientFailureFallsBack() {
        when(ollama.chat(anyString(), anyString(), any()))
                .thenThrow(new OllamaClient.OllamaException("Ollama call failed: Connection refused"));

        AiRouteAnswer a = service.askRoute("Hvor kan jeg købe parfume?", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("Connection refused");
        assertThat(a.model()).isNull();
        assertThat(a.shop()).isSameAs(dutyFree);
        assertThat(a.route()).isSameAs(toDutyFree);
        assertThat(a.interpretation()).isEqualTo("Nøgleordssøgning på \"parfume\": Duty Free Copenhagen T2");
    }

    @Test
    void malformedAnswerFallsBack() {
        when(ollama.chat(anyString(), anyString(), any()))
                .thenReturn("Sure! The passenger wants coffee, so Starbucks it is.");

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("JSON");
        assertThat(a.shop()).isSameAs(starbucks);
    }

    @Test
    void jsonWithoutTheContractFieldsFallsBack() {
        when(ollama.chat(anyString(), anyString(), any())).thenReturn("{\"shopId\": 10, \"toNodeId\": 31}");

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("JSON");
    }

    @Test
    void disabledAiNeverCallsOllama() {
        AiProperties off = new AiProperties(false, ENABLED.url(), ENABLED.model(), ENABLED.timeout(), false);
        service = new AiConciergeService(ollama, off, shops, nodes, routes, new ObjectMapper());

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("AI_ENABLED");
        assertThat(a.shop()).isSameAs(starbucks);
        verify(ollama, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void fallbackWithoutAnyMatchGivesNoShopAndNoRoute() {
        when(ollama.chat(anyString(), anyString(), any())).thenThrow(new OllamaClient.OllamaException("timeout"));

        AiRouteAnswer a = service.askRoute("xyzzy plugh", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("timeout");
        assertThat(a.shop()).isNull();
        assertThat(a.route()).isNull();
        assertThat(a.interpretation()).isNotBlank();
        verifyNoInteractions(routes);
    }

    @Test
    void fallbackDetectsDestinationNamedInQuestion() {
        when(ollama.chat(anyString(), anyString(), any())).thenThrow(new OllamaClient.OllamaException("timeout"));

        AiRouteAnswer a = service.askRoute("Jeg vil købe parfume på vej til gate B12", 24L, false);

        assertThat(a.shop()).isSameAs(dutyFree);
        assertThat(a.toNode()).isSameAs(gateB12);
        assertThat(a.route()).isSameAs(viaRoute);
        assertThat(a.interpretation()).contains("Gate B12");
        verify(routes).routeVia(24L, 36L, 31L, false);
    }

    @Test
    void noShopsMeansNoModelCall() {
        when(shops.findAll(any(Sort.class))).thenReturn(List.of());

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.shop()).isNull();
        verify(ollama, never()).chat(anyString(), anyString(), any());   // an empty enum is not a valid schema
    }

    @Test
    void accessibleOnlyIsPassedOnToRouting() {
        modelAnswers("coffee", "coffee", "Starbucks", true);
        when(routes.route(24L, 38L, true)).thenReturn(toStarbucks);

        service.askRoute("kaffe", 24L, true);

        verify(routes).route(24L, 38L, true);
    }

    @Test
    void unknownStartNodeIsNotFound() {
        when(nodes.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.askRoute("kaffe", 999L, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(ollama);
    }

    // ------------------------------------------------------------------ fixtures (ids are DB-generated, set here)

    private void modelAnswers(String english, String need, String shop, boolean fits) {
        when(ollama.chat(anyString(), anyString(), any())).thenReturn("{\"english\": \"" + english
                + "\", \"need\": \"" + need + "\", \"shop\": \"" + shop + "\", \"fits\": " + fits + "}");
    }

    static NavNode node(long id, String name, String terminal, NodeType type) {
        NavNode n = new NavNode(name, terminal, 0, 0, 0, type);
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    static Shop shop(long id, String name, ShopCategory category, String terminal, String description, NavNode node) {
        Shop s = new Shop(name, category, terminal, "Airside", 0, "06:00-22:00", description, node);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }
}
