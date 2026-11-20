package dk.airport.shop.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.shop.domain.ApiException;
import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.Route;
import dk.airport.shop.domain.Shop;
import dk.airport.shop.repository.NavNodeRepository;
import dk.airport.shop.repository.ShopRepository;
import dk.airport.shop.service.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * askRoute: "hvor kan jeg købe kaffe på vej til gate B12?" → shop + destination → Dijkstra route.
 *
 * <p>The LLM only <i>suggests</i> a shop and a destination from closed lists ({@link RoutePrompt}); the code validates
 * the suggestion and computes the route itself. When the AI is disabled, unreachable, too slow, or answers with
 * something invalid, the keyword fallback answers instead, so the query always works.
 *
 * <p>Deliberately not {@code @Transactional}: the LLM call can take up to the timeout (30 s), and a transaction would
 * hold a database connection for that long. Each repository call / RouteService call has its own short transaction.
 */
@Service
public class AskRouteService {

    private static final Logger log = LoggerFactory.getLogger(AskRouteService.class);

    private final ShopRepository shops;
    private final NavNodeRepository nodes;
    private final RouteService routes;
    private final OllamaClient ollama;
    private final ObjectMapper mapper;
    private final boolean aiEnabled;

    public AskRouteService(ShopRepository shops, NavNodeRepository nodes, RouteService routes, OllamaClient ollama,
                           ObjectMapper mapper, @Value("${app.ai.enabled:true}") boolean aiEnabled) {
        this.shops = shops;
        this.nodes = nodes;
        this.routes = routes;
        this.ollama = ollama;
        this.mapper = mapper;
        this.aiEnabled = aiEnabled;
    }

    public AskRouteAnswer askRoute(String question, Long fromNodeId, boolean accessibleOnly) {
        NavNode from = nodes.findById(fromNodeId).orElseThrow(() -> ApiException.notFound("NavNode", fromNodeId));
        List<Shop> allShops = shops.findAll(Sort.by("id"));
        List<NavNode> allNodes = nodes.findAll(Sort.by("id"));
        RoutePrompt prompt = new RoutePrompt(allShops, allNodes);

        // 1. Ask the model. Any problem becomes a fallback reason instead of an error.
        String fallbackReason;
        if (!aiEnabled) {
            fallbackReason = "AI er slået fra (AI_ENABLED=false)";
        } else {
            try {
                RoutePrompt.Choice choice = prompt.parse(ollama.chat(prompt.system(), prompt.user(question, from),
                        prompt.schema()), mapper);
                if (choice.shop() != null) {
                    log.info("askRoute answered by AI: need='{}', shop='{}', destination='{}'", choice.need(),
                            choice.shop().getName(),
                            choice.destination() == null ? "-" : choice.destination().getName());
                    return answer(from, choice.shop(), choice.destination(), accessibleOnly, true, null);
                }
                fallbackReason = "AI fandt ingen passende butik";
            } catch (ApiException e) {                         // UPSTREAM_UNAVAILABLE from OllamaClient
                fallbackReason = "AI utilgængelig: " + e.getMessage();
            } catch (RoutePrompt.InvalidAnswerException e) {   // invalid JSON / name not in the list
                fallbackReason = e.getMessage();
            }
        }

        // 2. Fallback without AI: keyword matching on the question.
        KeywordMatcher.Match match = KeywordMatcher.match(question, from, prompt.shops(), prompt.destinations());
        log.info("askRoute answered by fallback ({}): shop='{}', destination='{}'", fallbackReason,
                match.shop() == null ? "-" : match.shop().getName(),
                match.destination() == null ? "-" : match.destination().getName());
        return answer(from, match.shop(), match.destination(), accessibleOnly, false, fallbackReason);
    }

    private AskRouteAnswer answer(NavNode from, Shop shop, NavNode destination, boolean accessibleOnly,
                                  boolean aiUsed, String fallbackReason) {
        if (destination != null && destination.getId().equals(from.getId())) {
            destination = null;       // small models often name the start as destination when none was asked for
        }
        Route route;
        if (shop != null) {
            route = routes.routeVia(from.getId(), shop.getNode().getId(),
                    destination == null ? null : destination.getId(), accessibleOnly);
        } else if (destination != null) {
            route = routes.route(from.getId(), destination.getId(), accessibleOnly);   // no shop: just go there
        } else {
            route = null;
        }
        return new AskRouteAnswer(shop, destination, route, aiUsed, fallbackReason);
    }
}
