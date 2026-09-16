package dk.airport.shop.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * The model's answer as promised by {@link ConciergePrompt#answerSchema}:
 * {@code {"english", "need", "shop", "fits"}}. The shop name is a raw value from the model and still has to be
 * looked up by the caller.
 *
 * @param english the question translated to English (logged only; it is the model's first reasoning step)
 * @param need what the passenger is looking for in a few English words; empty when the model gave none
 * @param shop name of the shop the model suggests, or null when missing or {@code fits} is false
 * @param fits false when the model says no shop offers what the passenger wants
 */
record ConciergeAnswer(String english, String need, String shop, boolean fits) {

    /** Longest need that is passed on to the keyword scoring and the interpretation. */
    static final int MAX_NEED = 60;

    /**
     * Parses the message content. Ollama's schema-constrained decoding should always give a bare object, but a code
     * fence or text around it is tolerated (the first {@code {} ... last {@code }} is taken); returns empty when
     * there is no JSON object with a {@code need} or {@code shop} at all.
     */
    static Optional<ConciergeAnswer> parse(String content, ObjectMapper mapper) {
        if (content == null) {
            return Optional.empty();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(content.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || !(root.path("need").isTextual() || root.path("shop").isTextual())) {
            return Optional.empty();
        }
        String need = text(root.get("need"), MAX_NEED);
        if ("none".equalsIgnoreCase(need)) {
            need = "";
        }
        boolean fits = !root.path("fits").isBoolean() || root.path("fits").asBoolean();
        String shop = fits ? text(root.get("shop"), Integer.MAX_VALUE) : "";
        return Optional.of(new ConciergeAnswer(text(root.get("english"), 300), need, shop.isEmpty() ? null : shop,
                fits));
    }

    private static String text(JsonNode node, int max) {
        if (node == null || !node.isTextual()) {
            return "";
        }
        String t = ConciergePrompt.oneLine(node.asText());
        return t.length() <= max ? t : t.substring(0, max);
    }
}
