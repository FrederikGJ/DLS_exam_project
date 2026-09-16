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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * "Spørg om vej" (dev plan DP-17): a passenger asks in natural language where to go, a local language model
 * (Ollama, see {@link OllamaClient}) interprets the question, and the existing Dijkstra routing
 * ({@link RouteService}) produces the route.
 *
 * <p>Division of labour: the model <em>understands</em> (it translates the question, names the need in English
 * words and suggests one shop from a closed list, see {@link ConciergePrompt}); the code <em>decides</em>
 * ({@link KeywordMatcher} scores every shop on the question plus the model's words, gives the suggested shop a small
 * bonus, and ties go to the nearest shop). So the model can never route to something that does not exist, and a
 * wrong suggestion from a small model loses to a clear match in the question. The destination ("på vej til gate
 * B12") is found in the question itself.
 *
 * <p>The model is optional: when AI is disabled, Ollama is unreachable or slow (timeout) or the answer is not the
 * promised JSON, the same scoring runs on the question alone and the reply says so ({@code aiUsed=false},
 * {@code fallbackReason}). Only the routing part can fail the query, with the same NOT_FOUND / ROUTE_NOT_FOUND codes
 * as {@code route}.
 *
 * <p>Not transactional on purpose: a chat call can take seconds, and no database connection should be held
 * while waiting for it. The repositories and RouteService open their own short transactions.
 */
@Service
public class AiConciergeService {

    private static final Logger log = LoggerFactory.getLogger(AiConciergeService.class);

    /** Fallback reasons carry the client's error message, cut to this length. */
    static final int MAX_REASON = 200;

    private final OllamaClient ollama;
    private final AiProperties props;
    private final ShopRepository shops;
    private final NavNodeRepository nodes;
    private final RouteService routes;
    private final ObjectMapper mapper;

    public AiConciergeService(OllamaClient ollama, AiProperties props, ShopRepository shops,
                              NavNodeRepository nodes, RouteService routes, ObjectMapper mapper) {
        this.ollama = ollama;
        this.props = props;
        this.shops = shops;
        this.nodes = nodes;
        this.routes = routes;
        this.mapper = mapper;
    }

    /**
     * Answers a natural-language question with a shop and a route from {@code fromNodeId} (via the shop to the
     * destination named in the question, if any).
     *
     * @throws ApiException NOT_FOUND when the start node does not exist, ROUTE_NOT_FOUND when the shop cannot be
     *     reached (e.g. with {@code accessibleOnly})
     */
    public AiRouteAnswer askRoute(String question, Long fromNodeId, boolean accessibleOnly) {
        NavNode from = nodes.findById(fromNodeId).orElseThrow(() -> ApiException.notFound("NavNode", fromNodeId));
        List<Shop> allShops = shops.findAll(Sort.by("id"));
        List<NavNode> places = nodes.findAll(Sort.by("id")).stream()
                .filter(n -> ConciergePrompt.DESTINATION_TYPES.contains(n.getType()))
                .toList();
        String q = ConciergePrompt.oneLine(question);

        if (!props.enabled()) {
            return fallback(q, from, accessibleOnly, allShops, places, "AI er slået fra (AI_ENABLED=false)");
        }
        if (allShops.isEmpty()) {
            return fallback(q, from, accessibleOnly, allShops, places, "Der er ingen butikker at vælge imellem");
        }

        long started = System.nanoTime();
        String content;
        try {
            content = ollama.chat(ConciergePrompt.system(allShops), ConciergePrompt.user(q, from),
                    ConciergePrompt.answerSchema(allShops));
        } catch (OllamaClient.OllamaException e) {
            log.warn("askRoute: Ollama gave no answer after {} ms, using keyword fallback: {}", millisSince(started),
                    e.getMessage());
            return fallback(q, from, accessibleOnly, allShops, places, "Ollama svarede ikke: " + cut(e.getMessage()));
        }

        Optional<ConciergeAnswer> parsed = ConciergeAnswer.parse(content, mapper);
        if (parsed.isEmpty()) {
            log.warn("askRoute: model answer is not the promised JSON, using keyword fallback: {}",
                    cut(ConciergePrompt.oneLine(content)));
            return fallback(q, from, accessibleOnly, allShops, places, "Modellens svar var ikke gyldig JSON");
        }
        ConciergeAnswer answer = parsed.get();
        KeywordMatcher.Match match = KeywordMatcher.match(q, answer.need(), answer.shop(), allShops, places, from);
        log.info("askRoute: model read \"{}\" as need \"{}\", suggested {} (fits={}) in {} ms; candidates {}",
                answer.english(), answer.need(), answer.shop(), answer.fits(), millisSince(started),
                match.candidates().stream().map(Shop::getName).toList());

        NavNode to = match.toNode();
        String understood = answer.need().isEmpty() ? "" : " \"" + answer.need() + "\"";
        if (match.candidates().isEmpty()) {
            String interpretation = "Sprogmodellen forstod spørgsmålet" + understood
                    + ", men ingen butik i lufthavnen passer.";
            return new AiRouteAnswer(interpretation, null, to, null, true, null, ollama.model());
        }
        Shop shop = nearest(from, match.candidates(), accessibleOnly);
        Route route = routeTo(from, shop, to, accessibleOnly);
        String interpretation = "Sprogmodellen forstod" + (understood.isEmpty() ? " spørgsmålet" : understood) + ": "
                + shop.getName() + (to == null ? "" : " på vej til " + to.getName());
        return new AiRouteAnswer(interpretation, shop, to, route, true, null, ollama.model());
    }

    private AiRouteAnswer fallback(String question, NavNode from, boolean accessibleOnly, List<Shop> allShops,
                                   List<NavNode> places, String reason) {
        KeywordMatcher.Match match = KeywordMatcher.match(question, allShops, places, from);
        NavNode to = match.toNode();
        if (match.candidates().isEmpty()) {
            log.info("askRoute: keyword fallback found no shop for \"{}\" ({})", question, reason);
            return new AiRouteAnswer("Jeg fandt ingen butik, der matcher spørgsmålet.", null, to, null, false, reason,
                    null);
        }
        Shop shop = nearest(from, match.candidates(), accessibleOnly);
        Route route = routeTo(from, shop, to, accessibleOnly);
        String interpretation = "Nøgleordssøgning på \"" + String.join(", ", match.matchedWords()) + "\": "
                + shop.getName() + (to == null ? "" : " på vej til " + to.getName());
        log.info("askRoute: keyword fallback chose {} for \"{}\" ({})", shop.getName(), question, reason);
        return new AiRouteAnswer(interpretation, shop, to, route, false, reason, null);
    }

    /** Ties in the score are settled by walking distance from the passenger; unreachable shops are skipped. */
    private Shop nearest(NavNode from, List<Shop> candidates, boolean accessibleOnly) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        Shop best = null;
        int bestDistance = Integer.MAX_VALUE;
        ApiException unreachable = null;
        for (Shop s : candidates) {
            try {
                int d = routes.route(from.getId(), s.getNode().getId(), accessibleOnly).totalDistanceM();
                if (d < bestDistance) {
                    best = s;
                    bestDistance = d;
                }
            } catch (ApiException e) {
                unreachable = unreachable == null ? e : unreachable;
            }
        }
        if (best == null) {
            throw unreachable;              // every candidate is unreachable: ROUTE_NOT_FOUND as for route()
        }
        return best;
    }

    private Route routeTo(NavNode from, Shop shop, NavNode to, boolean accessibleOnly) {
        Long shopNode = shop.getNode().getId();
        return to == null
                ? routes.route(from.getId(), shopNode, accessibleOnly)
                : routes.routeVia(from.getId(), shopNode, to.getId(), accessibleOnly);
    }

    private static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_REASON ? s : s.substring(0, MAX_REASON - 1) + "…";
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
