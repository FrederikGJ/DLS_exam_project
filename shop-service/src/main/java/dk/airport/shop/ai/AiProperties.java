package dk.airport.shop.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the local-LLM concierge behind {@code askRoute}; see {@code app.ai.*} in application.yml, bound
 * from the environment variables OLLAMA_URL, OLLAMA_MODEL, AI_ENABLED and AI_TIMEOUT_MS.
 *
 * @param enabled false = Ollama is never called and askRoute answers with the keyword fallback straight away
 * @param url base URL of the Ollama server; the client posts to {@code {url}/api/chat}
 * @param model model name as Ollama knows it (must be present in the ollama image), e.g. {@code qwen2.5:1.5b}
 * @param timeout read timeout for one chat call; when it expires askRoute falls back to keyword search
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:11434") String url,
        @DefaultValue("qwen2.5:1.5b") String model,
        @DefaultValue("15s") Duration timeout) {
}
