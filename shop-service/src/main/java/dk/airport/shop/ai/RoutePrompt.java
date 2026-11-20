package dk.airport.shop.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.NodeType;
import dk.airport.shop.domain.Shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The prompt contract for askRoute: what the model is told, the JSON schema its answer must follow, and the
 * validation of that answer.
 *
 * <p>The model only ever sees a closed list of <b>names</b> (shops and destinations). The schema puts those names in
 * {@code enum}s, so Ollama cannot produce anything else, and {@link #parse} maps the chosen names back to the real
 * entities. Names instead of numeric ids, because small models mix up numbers (shop ids vs. node ids). The model
 * suggests - the code decides: a name that is not in the list is rejected and the keyword fallback is used.
 *
 * <pre>{"need": "coffee", "shop": "Starbucks (T2)", "destination": "Gate B12"}</pre>
 */
public class RoutePrompt {

    /** The value the model uses for "no shop fits" / "no destination named". */
    public static final String NONE = "NONE";

    /** Node types a passenger can name as a destination (junctions, elevators and shop nodes are left out). */
    public static final Set<NodeType> DESTINATION_TYPES =
            EnumSet.of(NodeType.GATE, NodeType.SECURITY, NodeType.ENTRANCE);

    /**
     * label -> shop, e.g. "Starbucks (T2)". Terminal in the label because the same name can exist in both terminals.
     */
    private final Map<String, Shop> shops = new LinkedHashMap<>();
    /** label -> node, e.g. "Gate B12". */
    private final Map<String, NavNode> destinations = new LinkedHashMap<>();

    public RoutePrompt(List<Shop> allShops, List<NavNode> allNodes) {
        for (Shop s : allShops) {
            if (s.getNode() != null) {                       // a shop without a node cannot be routed to
                shops.putIfAbsent(oneLine(s.getName()) + " (" + s.getTerminal() + ")", s);
            }
        }
        for (NavNode n : allNodes) {
            if (DESTINATION_TYPES.contains(n.getType())) {
                destinations.putIfAbsent(oneLine(n.getName()), n);
            }
        }
    }

    /** The shops the model can choose from (only shops with a node). Also used by the keyword fallback. */
    public Collection<Shop> shops() {
        return shops.values();
    }

    /** The destinations the model can choose from. Also used by the keyword fallback. */
    public Collection<NavNode> destinations() {
        return destinations.values();
    }

    /** System message: the task, the language note, and the two closed lists. */
    public String system() {
        StringBuilder sb = new StringBuilder();
        sb.append("You help passengers in an airport find a shop on their way.\n")
                .append("The question may be written in Danish or English.\n")
                .append("Answer with JSON only:\n")
                .append("- \"need\": what the passenger wants to buy or do, in 1-4 English words.\n")
                .append("- \"shop\": the shop from the list below that offers it, written exactly as in the list. ")
                .append("Prefer a shop in the passenger's terminal. Use NONE if no shop fits.\n")
                .append("- \"destination\": the gate, security or entrance the passenger is going to, written exactly ")
                .append("as in the list. Use NONE if the question does not name one.\n\n")
                .append("Shops (name (terminal) | category | description in Danish):\n");
        shops.forEach((label, s) -> sb.append("- ").append(label)
                .append(" | ").append(s.getCategory())
                .append(" | ").append(oneLine(s.getDescription())).append('\n'));
        sb.append("\nDestinations:\n");
        destinations.keySet().forEach(label -> sb.append("- ").append(label).append('\n'));
        return sb.toString();
    }

    /** User message: where the passenger is, and the question (collapsed to one line). */
    public String user(String question, NavNode from) {
        return "I am at " + from.getName() + " in terminal " + from.getTerminal() + ". " + oneLine(question);
    }

    /** JSON schema sent as Ollama's {@code format}. "need" comes first, so the model names the need before choosing. */
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("need", Map.of("type", "string"));
        properties.put("shop", Map.of("type", "string", "enum", withNone(shops.keySet())));
        properties.put("destination", Map.of("type", "string", "enum", withNone(destinations.keySet())));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.copyOf(properties.keySet()));
    }

    /** The validated answer. {@code shop} is null when the model said NONE, {@code destination} likewise. */
    public record Choice(String need, Shop shop, NavNode destination) {}

    /** Thrown when the model's answer cannot be used; the message becomes the fallback reason. */
    public static class InvalidAnswerException extends RuntimeException {
        public InvalidAnswerException(String message) {
            super(message);
        }
    }

    /**
     * Validates the model's answer before anything uses it: it must be a JSON object, and "shop" and "destination"
     * must be names from the lists (or NONE). Never trusts the schema alone.
     */
    public Choice parse(String json, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidAnswerException("AI-svaret er ikke gyldig JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidAnswerException("AI-svaret er ikke et JSON-objekt");
        }
        String shopName = root.path("shop").asText(NONE);
        String destinationName = root.path("destination").asText(NONE);
        Shop shop = NONE.equals(shopName) ? null : shops.get(shopName);
        NavNode destination = NONE.equals(destinationName) ? null : destinations.get(destinationName);
        if (!NONE.equals(shopName) && shop == null) {
            throw new InvalidAnswerException("AI valgte en ukendt butik: " + oneLine(shopName));
        }
        if (!NONE.equals(destinationName) && destination == null) {
            throw new InvalidAnswerException("AI valgte en ukendt destination: " + oneLine(destinationName));
        }
        return new Choice(root.path("need").asText(""), shop, destination);
    }

    private static List<String> withNone(Set<String> labels) {
        List<String> values = new ArrayList<>(labels);
        values.add(NONE);
        return values;
    }

    /** Collapses newlines/control characters so free text cannot break the prompt layout or a log line. */
    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }
}
