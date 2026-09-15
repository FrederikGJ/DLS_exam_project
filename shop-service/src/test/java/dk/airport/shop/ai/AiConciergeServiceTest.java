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
 * askRoute with a mocked {@link OllamaClient} (dev plan DP-17): the model's answer is validated against the data,
 * the route goes via the shop, and every failure mode (unknown id, client error, malformed JSON, AI disabled,
 * nothing matched) ends in the keyword fallback instead of an error.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiConciergeServiceTest {

    static final AiProperties ENABLED =
            new AiProperties(true, "http://localhost:11434", "qwen2.5:1.5b", Duration.ofSeconds(15));

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
        when(routes.route(24L, 15L, false)).thenReturn(new Route(List.of(), 500, 7, List.of()));
        when(routes.routeVia(eq(24L), anyLong(), eq(31L), eq(false))).thenReturn(viaRoute);
    }

    @Test
    void modelAnswerIsValidatedAndRoutedViaTheShop() {
        when(ollama.chat(anyString(), anyString())).thenReturn(
                "{\"shopId\": 10, \"toNodeId\": 31, \"interpretation\": \"Du vil have kaffe på vej til Gate B12\"}");

        AiRouteAnswer a = service.askRoute("Hvor finder jeg en kop kaffe på vej til gate B12?", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(starbucks);
        assertThat(a.toNode()).isSameAs(gateB12);
        assertThat(a.route()).isSameAs(viaRoute);
        assertThat(a.interpretation()).isEqualTo("Du vil have kaffe på vej til Gate B12");
        assertThat(a.fallbackReason()).isNull();
        assertThat(a.model()).isEqualTo("qwen2.5:1.5b");
        verify(routes).routeVia(24L, 38L, 31L, false);
    }

    @Test
    void promptListsShopsAndDestinationsButNoJunctions() {
        when(ollama.chat(anyString(), anyString())).thenReturn("{\"shopId\": 10}");

        service.askRoute("kaffe\n\tnu", 24L, false);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(ollama).chat(system.capture(), user.capture());
        assertThat(system.getValue())
                .contains("10 | Starbucks | FOOD | T2 | Airside | 0 | Kaffe, te og bagværk.")
                .contains("31 | Gate B12 | GATE | T2 | 0")
                .contains("24 | Security T2 | SECURITY | T2 | 0")
                .doesNotContain("Junction T2 Central")
                .doesNotContain("Starbucks T2 | SHOP")
                .contains("{\"shopId\": <id or null>, \"toNodeId\": <id or null>, \"interpretation\": \"<sentence>\"}");
        assertThat(user.getValue()).contains("The passenger is at: Security T2 (terminal T2, floor 0)")
                .contains("Question: \"kaffe nu\"");        // control characters collapsed to one space
    }

    @Test
    void idsAsStringsCodeFenceAndMissingInterpretationAreTolerated() {
        when(ollama.chat(anyString(), anyString()))
                .thenReturn("```json\n{\"shopId\": \"10\", \"toNodeId\": null}\n```");

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.shop()).isSameAs(starbucks);
        assertThat(a.toNode()).isNull();
        assertThat(a.route()).isSameAs(toStarbucks);
        assertThat(a.interpretation()).contains("Starbucks");
    }

    @Test
    void unknownDestinationFromModelIsIgnored() {
        when(ollama.chat(anyString(), anyString())).thenReturn("{\"shopId\": 10, \"toNodeId\": 25}");   // a junction

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.toNode()).isNull();
        assertThat(a.route()).isSameAs(toStarbucks);
        verify(routes, never()).routeVia(anyLong(), anyLong(), anyLong(), any(Boolean.class));
    }

    @Test
    void unknownShopIdFallsBackToKeywordsAndNearestShop() {
        when(ollama.chat(anyString(), anyString()))
                .thenReturn("{\"shopId\": 999, \"toNodeId\": null, \"interpretation\": \"?\"}");

        AiRouteAnswer a = service.askRoute("Hvor finder jeg en kop kaffe?", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("999");
        assertThat(a.model()).isNull();
        // Starbucks and Lagkagehuset both score FOOD + "kaffe" + terminal T2; the nearer one (130 m < 200 m) wins
        assertThat(a.shop()).isSameAs(starbucks);
        assertThat(a.route()).isSameAs(toStarbucks);
        assertThat(a.interpretation()).contains("kaffe").contains("Starbucks");
    }

    @Test
    void clientFailureFallsBack() {
        when(ollama.chat(anyString(), anyString()))
                .thenThrow(new OllamaClient.OllamaException("Ollama call failed: Connection refused"));

        AiRouteAnswer a = service.askRoute("Hvor kan jeg købe parfume?", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("Connection refused");
        assertThat(a.shop()).isSameAs(dutyFree);
        assertThat(a.route()).isSameAs(toDutyFree);
    }

    @Test
    void malformedAnswerFallsBack() {
        when(ollama.chat(anyString(), anyString()))
                .thenReturn("Sure! The passenger wants coffee, so Starbucks it is.");

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("JSON");
        assertThat(a.shop()).isSameAs(starbucks);
    }

    @Test
    void nullShopIdFromModelFallsBack() {
        when(ollama.chat(anyString(), anyString()))
                .thenReturn("{\"shopId\": null, \"toNodeId\": null, \"interpretation\": \"Ingen butik\"}");

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("ingen passende butik");
        assertThat(a.shop()).isSameAs(starbucks);
    }

    @Test
    void disabledAiNeverCallsOllama() {
        AiProperties off = new AiProperties(false, ENABLED.url(), ENABLED.model(), ENABLED.timeout());
        service = new AiConciergeService(ollama, off, shops, nodes, routes, new ObjectMapper());

        AiRouteAnswer a = service.askRoute("kaffe", 24L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("AI_ENABLED");
        assertThat(a.shop()).isSameAs(starbucks);
        verify(ollama, never()).chat(anyString(), anyString());
    }

    @Test
    void fallbackWithoutAnyMatchGivesNoShopAndNoRoute() {
        when(ollama.chat(anyString(), anyString())).thenThrow(new OllamaClient.OllamaException("timeout"));

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
        when(ollama.chat(anyString(), anyString())).thenThrow(new OllamaClient.OllamaException("timeout"));

        AiRouteAnswer a = service.askRoute("Jeg vil købe parfume på vej til gate B12", 24L, false);

        assertThat(a.shop()).isSameAs(dutyFree);
        assertThat(a.toNode()).isSameAs(gateB12);
        assertThat(a.route()).isSameAs(viaRoute);
        assertThat(a.interpretation()).contains("Gate B12");
        verify(routes).routeVia(24L, 36L, 31L, false);
    }

    @Test
    void accessibleOnlyIsPassedOnToRouting() {
        when(ollama.chat(anyString(), anyString())).thenReturn("{\"shopId\": 10}");
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
