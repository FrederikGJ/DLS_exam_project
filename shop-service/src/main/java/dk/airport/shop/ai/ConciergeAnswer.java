package dk.airport.shop.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * The model's answer as promised by {@link ConciergePrompt}: {@code {"shopId", "toNodeId", "interpretation"}}.
 * Ids are raw values from the model and still have to be checked against the database by the caller.
 *
 * @param shopId id of the chosen shop, or null when the model found no match (or sent something unusable)
 * @param toNodeId id of the destination node named in the question, or null
 * @param interpretation the model's one-sentence reading of the question, or null when missing
 */
record ConciergeAnswer(Long shopId, Long toNodeId, String interpretation) {

    /** Longest interpretation that is passed on to the client. */
    static final int MAX_INTERPRETATION = 300;

    /**
     * Parses the message content. Tolerates a code fence or text around the object (the first {@code {} ... {@code }}
     * is taken) and ids sent as strings; returns empty when there is no JSON object at all.
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
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new ConciergeAnswer(id(root.get("shopId")), id(root.get("toNodeId")),
                text(root.get("interpretation"))));
    }

    private static Long id(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && node.asText().trim().matches("\\d{1,18}")) {
            return Long.parseLong(node.asText().trim());
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String t = ConciergePrompt.oneLine(node.asText());
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= MAX_INTERPRETATION ? t : t.substring(0, MAX_INTERPRETATION - 1) + "…";
    }
}
