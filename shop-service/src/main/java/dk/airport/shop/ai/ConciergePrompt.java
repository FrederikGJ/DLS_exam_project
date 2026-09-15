package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.NodeType;
import dk.airport.shop.domain.Shop;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the two chat messages for the concierge model. The system message lists every shop and every place a
 * passenger can name as a destination, both straight from the database, and pins the answer to one JSON object:
 * <pre>{"shopId": &lt;id or null&gt;, "toNodeId": &lt;id or null&gt;, "interpretation": "&lt;one sentence&gt;"}</pre>
 * The instructions are English (small models follow English instructions best); the interpretation is asked for
 * in Danish because that is what the UI shows. Rows are ordered by id, so the same data gives the same prompt and
 * Ollama can reuse its prompt cache between questions.
 */
public final class ConciergePrompt {

    /**
     * Node types a passenger can name as a destination. Junctions are only routing helpers, and shop nodes are
     * reached through {@code shopId}, so both are left out to keep the prompt short and unambiguous.
     */
    public static final Set<NodeType> DESTINATION_TYPES =
            EnumSet.of(NodeType.GATE, NodeType.SECURITY, NodeType.ENTRANCE, NodeType.ELEVATOR);

    /** Descriptions are trimmed to this many characters in the prompt (they only need to carry the keywords). */
    static final int MAX_DESCRIPTION = 70;

    private ConciergePrompt() {
    }

    /** The system message: role, the shop and place lists and the JSON contract. */
    public static String system(List<Shop> shops, List<NavNode> places) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("You are the concierge of Copenhagen Airport. A passenger tells you, in Danish or English, ")
                .append("what they are looking for and possibly where they are going next. ")
                .append("Pick the ONE shop from the shop list that best matches what they want. ")
                .append("If they also name a place such as a gate, security or an entrance, pick that place ")
                .append("from the place list.\n\n");

        sb.append("Shops (id | name | category | terminal | zone | floor | description):\n");
        for (Shop s : shops) {
            sb.append(s.getId()).append(" | ").append(s.getName()).append(" | ").append(s.getCategory())
                    .append(" | ").append(s.getTerminal()).append(" | ").append(s.getZone())
                    .append(" | ").append(s.getFloor()).append(" | ").append(shortDescription(s)).append('\n');
        }
        sb.append("\nPlaces (id | name | type | terminal | floor):\n");
        for (NavNode n : places) {
            sb.append(n.getId()).append(" | ").append(n.getName()).append(" | ").append(n.getType())
                    .append(" | ").append(n.getTerminal()).append(" | ").append(n.getFloor()).append('\n');
        }

        sb.append("\nCategory guide: FOOD = coffee, tea, food, drinks, bakery, restaurant, snacks; ")
                .append("DUTY_FREE = perfume, alcohol, chocolate, tax free; ")
                .append("RETAIL = books, magazines, clothes, toys, electronics, gifts; ")
                .append("SERVICE = pharmacy, medicine, currency exchange, luggage wrapping and storage; ")
                .append("LOUNGE = lounge, rest, quiet.\n\n");
        sb.append("Rules:\n")
                .append("1. Use only ids from the lists above. Never invent an id.\n")
                .append("2. Prefer a shop in the terminal of the passenger's destination; otherwise a shop in the ")
                .append("terminal the passenger is in now.\n")
                .append("3. If no shop fits, use null for shopId. If no place is named, use null for toNodeId.\n")
                .append("4. \"interpretation\" is ONE short sentence in Danish that says what you understood, ")
                .append("for example \"Du vil have kaffe på vej til Gate B12\".\n")
                .append("Answer with only this JSON object and nothing else:\n")
                .append("{\"shopId\": <id or null>, \"toNodeId\": <id or null>, \"interpretation\": \"<sentence>\"}");
        return sb.toString();
    }

    /** The user message: where the passenger is and the question itself (free text, sanitised to one line). */
    public static String user(String question, NavNode from) {
        return "The passenger is at: " + from.getName() + " (terminal " + from.getTerminal() + ", floor "
                + from.getFloor() + ").\nQuestion: \"" + oneLine(question) + "\"";
    }

    /** Collapses whitespace and control characters, so a question cannot break the prompt layout or a log line. */
    public static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }

    private static String shortDescription(Shop s) {
        String d = s.getDescription() == null ? "" : oneLine(s.getDescription());
        return d.length() <= MAX_DESCRIPTION ? d : d.substring(0, MAX_DESCRIPTION - 1).trim() + "…";
    }
}
