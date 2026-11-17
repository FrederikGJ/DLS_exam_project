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
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dk.airport.shop.ai.KeywordMatcherTest.node;
import static dk.airport.shop.ai.KeywordMatcherTest.shop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** askRoute's decision logic with a mocked OllamaClient (no HTTP, no database). */
class AskRouteServiceTest {

    final NavNode securityT2 = node(1, "Security T2", "T2", NodeType.SECURITY);
    final NavNode gateB12 = node(2, "Gate B12", "T2", NodeType.GATE);
    final Shop starbucks = shop(10, "Starbucks", ShopCategory.FOOD, "T2", "Kaffe, te og bagværk.");
    final Shop whsmith = shop(11, "WHSmith", ShopCategory.RETAIL, "T2", "Bøger og magasiner.");
    final Route route = new Route(List.of(), 100, 2, List.of());

    final ShopRepository shops = mock(ShopRepository.class);
    final NavNodeRepository nodes = mock(NavNodeRepository.class);
    final RouteService routes = mock(RouteService.class);
    final OllamaClient ollama = mock(OllamaClient.class);

    AskRouteService service(boolean aiEnabled) {
        return new AskRouteService(shops, nodes, routes, ollama, new ObjectMapper(), aiEnabled);
    }

    @BeforeEach
    void data() {
        when(nodes.findById(1L)).thenReturn(Optional.of(securityT2));
        when(nodes.findAll(any(Sort.class))).thenReturn(List.of(securityT2, gateB12));
        when(shops.findAll(any(Sort.class))).thenReturn(List.of(starbucks, whsmith));
        when(routes.routeVia(anyLong(), anyLong(), any(), anyBoolean())).thenReturn(route);
    }

    void modelAnswers(String json) {
        when(ollama.chat(anyString(), anyString(), any())).thenReturn(json);
    }

    @Test
    void happyPathUsesTheModelsChoice() {
        modelAnswers("{\"need\":\"books\",\"shop\":\"WHSmith (T2)\",\"destination\":\"Gate B12\"}");

        AskRouteAnswer a = service(true).askRoute("hvor kan jeg købe en bog på vej til gate B12", 1L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.fallbackReason()).isNull();
        assertThat(a.shop()).isEqualTo(whsmith);
        assertThat(a.destination()).isEqualTo(gateB12);
        assertThat(a.route()).isSameAs(route);
        verify(routes).routeVia(1L, whsmith.getNode().getId(), 2L, false);
    }

    @Test
    void destinationEqualToTheStartIsIgnored() {
        modelAnswers("{\"need\":\"books\",\"shop\":\"WHSmith (T2)\",\"destination\":\"Security T2\"}");

        AskRouteAnswer a = service(true).askRoute("jeg vil købe en bog", 1L, false);

        assertThat(a.aiUsed()).isTrue();
        assertThat(a.destination()).isNull();
        verify(routes).routeVia(1L, whsmith.getNode().getId(), null, false);   // route ends at the shop
    }

    @Test
    void invalidJsonFallsBackToKeywords() {
        modelAnswers("Sure! Go to Starbucks.");

        AskRouteAnswer a = service(true).askRoute("kaffe", 1L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("JSON");
        assertThat(a.shop()).isEqualTo(starbucks);
    }

    @Test
    void ollamaDownOrTimeoutFallsBackToKeywords() {
        when(ollama.chat(anyString(), anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Ollama unavailable"));

        AskRouteAnswer a = service(true).askRoute("coffee before gate B12", 1L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).startsWith("AI utilgængelig");
        assertThat(a.shop()).isEqualTo(starbucks);
        assertThat(a.destination()).isEqualTo(gateB12);
    }

    @Test
    void shopThatIsNotInTheListIsRejected() {
        modelAnswers("{\"need\":\"burger\",\"shop\":\"Burger King (T2)\",\"destination\":\"NONE\"}");

        AskRouteAnswer a = service(true).askRoute("kaffe", 1L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("ukendt butik");
        assertThat(a.shop()).isEqualTo(starbucks);             // from the fallback, never the invented shop
    }

    @Test
    void disabledAiNeverCallsOllama() {
        AskRouteAnswer a = service(false).askRoute("kaffe", 1L, false);

        assertThat(a.aiUsed()).isFalse();
        assertThat(a.fallbackReason()).contains("AI_ENABLED=false");
        verify(ollama, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void noShopAndNoDestinationGivesNoRoute() {
        modelAnswers("{\"need\":\"toilet\",\"shop\":\"NONE\",\"destination\":\"NONE\"}");

        AskRouteAnswer a = service(true).askRoute("toilet", 1L, false);

        assertThat(a.shop()).isNull();
        assertThat(a.route()).isNull();
        assertThat(a.fallbackReason()).isEqualTo("AI fandt ingen passende butik");
        verify(routes, never()).routeVia(anyLong(), anyLong(), isNull(), eq(false));
    }

    @Test
    void unknownStartNodeIsNotFoundAndOllamaIsNotCalled() {
        when(nodes.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(true).askRoute("kaffe", 99L, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(ollama, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void schemaIsAClosedListOfNamesPlusNone() {
        RoutePrompt prompt = new RoutePrompt(List.of(starbucks, whsmith), List.of(securityT2, gateB12));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) prompt.schema().get("properties");

        assertThat(properties.get("shop").get("enum")).isEqualTo(List.of("Starbucks (T2)", "WHSmith (T2)", "NONE"));
        assertThat(properties.get("destination").get("enum")).isEqualTo(List.of("Security T2", "Gate B12", "NONE"));
        assertThat(prompt.system()).contains("Danish or English");
    }
}
