package dk.airport.shop.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.shop.domain.ApiException;
import dk.airport.shop.domain.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Synchronous call to Ollama's chat API ({@code POST /api/chat}) - same pattern as booking-service's FlightClient.
 * {@code format} is a JSON schema: Ollama constrains the model's output to it, so the answer is JSON with exactly
 * the fields (and enum values) the schema allows. Any failure (not reachable, timeout, HTTP error, empty answer)
 * becomes {@link ErrorCode#UPSTREAM_UNAVAILABLE}; the caller then uses the keyword fallback.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient restClient;
    private final String model;

    public OllamaClient(@Value("${app.ai.url}") String url,
                        @Value("${app.ai.model}") String model,
                        @Value("${app.ai.timeout:30s}") Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .build();
        this.model = model;
    }

    /** Sends one system + one user message and returns the model's answer (a JSON document as text). */
    public String chat(String systemPrompt, String userMessage, Map<String, Object> format) {
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,                       // one JSON response instead of a token stream
                "format", format,                      // JSON schema the answer must follow
                "options", Map.of("temperature", 0),   // deterministic: same question -> same answer
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)));
        long start = System.nanoTime();
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Ollama call failed after {} ms: {}", millisSince(start), e.getMessage());
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Ollama unavailable");
        }
        String content = response == null ? "" : response.path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Ollama returned an empty answer");
        }
        log.info("Ollama {} answered in {} ms", model, millisSince(start));
        return content;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
