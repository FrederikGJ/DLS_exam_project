package dk.airport.shop.config;

import dk.airport.shop.ai.AiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Binds {@code app.ai.*} ({@link AiProperties}) and gives Spring Boot's auto-configured {@code RestClient.Builder}
 * the timeouts for the Ollama call (dev plan DP-17).
 *
 * <p>The Ollama chat is the only outbound HTTP call this service makes (Spring Security fetches the JWKS with its
 * own RestTemplate), so the builder-wide request-factory settings are effectively the AI settings. Configuring
 * them here rather than with a hand-built request factory in {@code OllamaClient} keeps the builder open for
 * {@code MockRestServiceServer} in the integration tests, which replaces the request factory to stub /api/chat.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /** Connecting must fail fast: a host that is down or black-holed should give the keyword fallback, not a hang. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    ClientHttpRequestFactorySettings clientHttpRequestFactorySettings(AiProperties ai) {
        return ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(ai.timeout());
    }
}
