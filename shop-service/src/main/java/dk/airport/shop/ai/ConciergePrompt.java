package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.NodeType;
import dk.airport.shop.domain.Shop;
import dk.airport.shop.domain.ShopCategory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the chat request for the concierge model: a system message with the shop list (straight from the
 * database), a user message with the passenger's terminal and question, and a JSON schema that pins the answer to
 * <pre>{"english": "...", "need": "...", "shop": "&lt;a shop name from the list&gt;", "fits": true|false}</pre>
 * Ollama constrains decoding to the schema, so {@code shop} can only be one of the listed names (an enum) and the
 * answer is always valid JSON.
 *
 * <p>Why this shape (measured with qwen2.5:1.5b on 33 test questions, see docs/architecture.md "AI"): asking a
 * 1.5B model for ids made it confuse shop ids with node ids and answer {@code null} for most questions. Names from
 * an enum remove the id problem, and letting the model first translate the question ({@code english}) and name the
 * need in plain English words ({@code need}) works as a short chain of thought. The model's shop is still only a
 * suggestion: {@link KeywordMatcher} scores every shop on the question plus the model's need and gives the
 * suggested shop a small bonus, which fixed most of the model's remaining mistakes (wrong terminal, a juice bar for
 * a headache) without losing what the model adds (e.g. "noget mod køresyge" becomes "medicine").
 *
 * <p>The instructions are English (small models follow English instructions best). Destinations ("på vej til gate
 * B12") are not asked for: the model was unreliable at them, and {@link KeywordMatcher#findPlace} finds them
 * deterministically. Rows are ordered by id, so the same data gives the same prompt and Ollama can reuse its prompt
 * cache between questions.
 */
public final class ConciergePrompt {

    /**
     * Node types a passenger can name as a destination. Junctions are only routing helpers, and shop nodes are
     * reached through the shop, so both are left out.
     */
    public static final Set<NodeType> DESTINATION_TYPES =
            EnumSet.of(NodeType.GATE, NodeType.SECURITY, NodeType.ENTRANCE, NodeType.ELEVATOR);

    /** Descriptions are trimmed to this many characters in the prompt (they only need to carry the keywords). */
    static final int MAX_DESCRIPTION = 100;

    /** What each category covers, in the English words the model is asked to use for {@code need}. */
    static final Map<ShopCategory, String> CATEGORY_GUIDE = new EnumMap<>(Map.of(
            ShopCategory.FOOD, "food and drink: coffee, tea, juice, bakery, cake, meals, beer, snacks",
            ShopCategory.DUTY_FREE, "tax free: perfume, cosmetics, alcohol, whisky, wine, chocolate, candy",
            ShopCategory.RETAIL, "shopping: books, magazines, clothes, shirts, toys, electronics, chargers, gifts",
            ShopCategory.SERVICE, "services: pharmacy, medicine, currency exchange, cash, luggage wrapping and storage",
            ShopCategory.LOUNGE, "lounge: rest, relax, quiet place, comfortable chairs"));

    private ConciergePrompt() {
    }

    /** The system message: the four answer steps, the category guide and the shop list. */
    public static String system(List<Shop> shops) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("You help passengers in Copenhagen Airport find the right shop. ")
                .append("The passenger writes in Danish or English.\n")
                .append("Step 1: \"english\" = the question translated to English.\n")
                .append("Step 2: \"need\" = what the passenger wants to buy or do, in 1-4 English words.\n")
                .append("Step 3: \"shop\" = the shop from the list that offers it. ")
                .append("Prefer the passenger's terminal when two shops fit.\n")
                .append("Step 4: \"fits\" = false if no shop in the list offers it (e.g. toilets, parking), ")
                .append("otherwise true.\n\n");

        sb.append("Categories: ");
        CATEGORY_GUIDE.forEach((category, words) -> sb.append(category).append(" = ").append(words).append("; "));
        sb.setLength(sb.length() - 2);
        sb.append("\n\nShops (name | terminal | category | description in Danish):\n");
        for (Shop s : shops) {
            String guide = CATEGORY_GUIDE.get(s.getCategory());
            sb.append("- ").append(oneLine(s.getName()))
                    .append(" | terminal ").append(s.getTerminal())
                    .append(" | ").append(s.getCategory()).append(" (").append(guide, 0, guide.indexOf(':')).append(')')
                    .append(" | ").append(shortDescription(s)).append('\n');
        }
        return sb.toString();
    }

    /** The user message: the passenger's terminal and the question itself (free text, sanitised to one line). */
    public static String user(String question, NavNode from) {
        return "I am in terminal " + from.getTerminal() + ". " + oneLine(question);
    }

    /**
     * The JSON schema sent as Ollama's {@code format}. Property order matters: the model writes the fields in this
     * order, so the translation and the need come before the choice of shop.
     */
    public static Map<String, Object> answerSchema(List<Shop> shops) {
        Set<String> names = new LinkedHashSet<>();
        shops.forEach(s -> names.add(oneLine(s.getName())));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("english", Map.of("type", "string"));
        properties.put("need", Map.of("type", "string"));
        properties.put("shop", Map.of("type", "string", "enum", List.copyOf(names)));
        properties.put("fits", Map.of("type", "boolean"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.copyOf(properties.keySet()));
    }

    /** Collapses whitespace and control characters, so a question cannot break the prompt layout or a log line. */
    public static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }

    private static String shortDescription(Shop s) {
        String d = oneLine(s.getDescription());
        return d.length() <= MAX_DESCRIPTION ? d : d.substring(0, MAX_DESCRIPTION - 1).trim() + "…";
    }
}
