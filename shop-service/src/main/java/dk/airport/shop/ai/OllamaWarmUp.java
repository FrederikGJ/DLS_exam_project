package dk.airport.shop.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Loads the concierge model into Ollama once shop-service has started, so the first "Spørg om vej" is not the one
 * that pays for loading the model (more than the 15 s chat timeout while the whole stack boots on a laptop).
 *
 * <p>Best effort and in the background: it runs on a virtual thread (readiness is never delayed), tries a few times
 * because Ollama may start after this service (compose has no depends_on on purpose, and in Kubernetes Ollama is an
 * optional component), and only logs when it gives up. Nothing happens when AI is disabled or {@code app.ai.warm-up}
 * is false (the integration tests stub Ollama and turn it off).
 */
@Component
public class OllamaWarmUp {

    private static final Logger log = LoggerFactory.getLogger(OllamaWarmUp.class);

    static final int ATTEMPTS = 6;
    static final Duration PAUSE = Duration.ofSeconds(10);

    private final OllamaClient ollama;
    private final AiProperties props;

    public OllamaWarmUp(OllamaClient ollama, AiProperties props) {
        this.ollama = ollama;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.enabled() && props.warmUp()) {
            Thread.ofVirtual().name("ollama-warm-up").start(this::warmUp);
        }
    }

    void warmUp() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            try {
                ollama.loadModel();
                log.info("Ollama warm-up: {} is loaded ({} ms, attempt {})", ollama.model(),
                        (System.nanoTime() - started) / 1_000_000, attempt);
                return;
            } catch (OllamaClient.OllamaException e) {
                log.debug("Ollama warm-up attempt {} failed: {}", attempt, e.getMessage());
            }
            try {
                Thread.sleep(PAUSE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("Ollama warm-up gave up after {} attempts; the first askRoute will load the model (or fall back)",
                ATTEMPTS);
    }
}
