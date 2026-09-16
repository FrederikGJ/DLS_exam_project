package dk.airport.shop.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Thin client for Ollama's chat API ({@code POST /api/chat}), used by {@link AiConciergeService}.
 *
 * <p>Every call passes a JSON schema as {@code format} (Ollama's structured outputs: the server constrains decoding
 * to the schema, so the answer is valid JSON with the listed fields and enum values) and runs without streaming and
 * with {@code temperature 0}, so the same prompt gives the same answer. The client is built
 * from Spring Boot's auto-configured {@link RestClient.Builder} on purpose: the connect/read timeouts come from
 * {@code AiProperties} through {@code config/AiConfig}, and tests bind a {@code MockRestServiceServer} to the same
 * builder, so the whole askRoute path can be exercised without a running Ollama.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    /** The answer is a three-field JSON object; this cap stops a confused model from rambling on. */
    static final int MAX_ANSWER_TOKENS = 160;

    private final RestClient restClient;
    private final AiProperties props;

    public OllamaClient(RestClient.Builder builder, AiProperties props) {
        this.restClient = builder.baseUrl(props.url()).build();
        this.props = props;
    }

    /** Name of the model every call is sent to (for logs and the GraphQL answer). */
    public String model() {
        return props.model();
    }

    /**
     * One chat completion constrained to a JSON schema. Returns the assistant's message content, a JSON document.
     *
     * @param format the JSON schema the answer must follow (see {@code ConciergePrompt.answerSchema})
     * @throws OllamaException when Ollama cannot be reached, does not answer within the timeout, answers with an
     *     HTTP error (e.g. 404 when the model is missing) or returns no message content
     */
    public String chat(String systemPrompt, String userMessage, Map<String, Object> format) {
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "stream", false,
                "format", format,
                "options", Map.of("temperature", 0, "num_predict", MAX_ANSWER_TOKENS),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)));
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new OllamaException("Ollama is not reachable at " + props.url() + " (" + rootCause(e) + ")", e);
        } catch (RestClientException e) {
            throw new OllamaException("Ollama call failed: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new OllamaException("Ollama returned an empty response");
        }
        JsonNode content = response.path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new OllamaException("Ollama answer has no message content: " + response);
        }
        log.info("Ollama {} answered in {} ms (prompt {} tokens, answer {} tokens)", props.model(),
                response.path("total_duration").asLong() / 1_000_000, response.path("prompt_eval_count").asInt(),
                response.path("eval_count").asInt());
        return content.asText();
    }

    /**
     * Asks Ollama to load the model into memory without generating anything ({@code POST /api/generate} with only the
     * model name). Loading takes a few seconds on an idle laptop but more than the chat timeout while the whole stack
     * boots, so the first question would otherwise always get the keyword fallback. The model stays loaded for
     * Ollama's {@code OLLAMA_KEEP_ALIVE}.
     *
     * @throws OllamaException when Ollama cannot be reached or does not finish loading within the timeout
     */
    public void loadModel() {
        try {
            restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", props.model()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new OllamaException("Loading " + props.model() + " failed: " + e.getMessage(), e);
        }
    }

    /** E.g. "HttpConnectTimeoutException: HTTP connect timed out"; the I/O message alone is often null. */
    private static String rootCause(ResourceAccessException e) {
        Throwable root = e.getMostSpecificCause();
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    /** Anything that stops a chat call from producing an answer; askRoute turns it into the keyword fallback. */
    public static class OllamaException extends RuntimeException {
        public OllamaException(String message) {
            super(message);
        }

        public OllamaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
