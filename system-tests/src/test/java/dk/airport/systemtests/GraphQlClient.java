package dk.airport.systemtests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Minimal GraphQL-over-HTTP client on {@code java.net.http}. The system test is a black-box client of the services,
 * so it deliberately shares no code with them: a request is a JSON body {@code {query, variables}} with an optional
 * Bearer token, a response is {@code {data, errors}} - exactly what the frontend sends and receives.
 */
final class GraphQlClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String url;

    GraphQlClient(String url, ObjectMapper objectMapper) {
        this.url = url;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes the document and returns {@code data}; fails the test if the response carries GraphQL errors.
     *
     * @param authorization {@code Authorization} header value or {@code null} for an anonymous request
     */
    JsonNode query(String authorization, String document, Map<String, Object> variables) {
        JsonNode response = execute(authorization, document, variables);
        if (response.has("errors")) {
            throw new AssertionError("GraphQL errors for " + url + ": " + response.get("errors"));
        }
        return response.path("data");
    }

    /** Executes the document and returns the machine readable code of the single expected error. */
    String errorCode(String authorization, String document, Map<String, Object> variables) {
        JsonNode errors = execute(authorization, document, variables).path("errors");
        if (!errors.isArray() || errors.size() != 1) {
            throw new AssertionError("Expected exactly one GraphQL error from " + url + " but got: " + errors);
        }
        return errors.get(0).path("extensions").path("code").asText();
    }

    /** Executes the document and returns the whole response ({@code data} and/or {@code errors}). */
    JsonNode execute(String authorization, String document, Map<String, Object> variables) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(document, variables)));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AssertionError("HTTP " + response.statusCode() + " from " + url + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Request to " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + url, e);
        }
    }

    private String body(String document, Map<String, Object> variables) {
        try {
            return objectMapper.writeValueAsString(Map.of("query", document, "variables", variables));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise GraphQL variables", e);
        }
    }
}
