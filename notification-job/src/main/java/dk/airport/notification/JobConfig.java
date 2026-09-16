package dk.airport.notification;

import java.time.Duration;
import java.util.Map;

/**
 * Configuration of one job run, read from environment variables. The defaults match docker-compose and the
 * services' application.yml, so the job runs against a local stack without any variable set.
 * <pre>
 *  RABBITMQ_HOST       localhost        RABBITMQ_USERNAME  airport
 *  RABBITMQ_PORT       5672             RABBITMQ_PASSWORD  airport
 *  NOTIFICATION_QUEUE  notifications
 *  MAX_MESSAGES        100              stop after this many deliveries (bounds the run time of one job)
 *  IDLE_TIMEOUT_MS     5000             stop when no delivery has arrived for this long (the queue is drained)
 * </pre>
 * An invalid value (not a number, out of range) is a configuration error: the job exits with code 2 before it
 * connects to anything.
 */
public record JobConfig(String host, int port, String username, String password, String queue, int maxMessages,
                        Duration idleTimeout) {

    static final String DEFAULT_HOST = "localhost";
    static final int DEFAULT_PORT = 5672;
    static final String DEFAULT_USERNAME = "airport";
    static final String DEFAULT_PASSWORD = "airport";
    static final String DEFAULT_QUEUE = "notifications";
    static final int DEFAULT_MAX_MESSAGES = 100;
    static final long DEFAULT_IDLE_TIMEOUT_MS = 5000;

    private static final int MAX_PORT = 65_535;

    /**
     * Reads the configuration from the given environment (normally {@code System.getenv()}).
     *
     * @param env environment variables; missing or blank entries fall back to the defaults
     * @return the validated configuration
     * @throws IllegalArgumentException if a numeric variable is not a number or out of range
     */
    public static JobConfig fromEnv(Map<String, String> env) {
        return new JobConfig(
                text(env, "RABBITMQ_HOST", DEFAULT_HOST),
                number(env, "RABBITMQ_PORT", DEFAULT_PORT, MAX_PORT),
                text(env, "RABBITMQ_USERNAME", DEFAULT_USERNAME),
                text(env, "RABBITMQ_PASSWORD", DEFAULT_PASSWORD),
                text(env, "NOTIFICATION_QUEUE", DEFAULT_QUEUE),
                number(env, "MAX_MESSAGES", DEFAULT_MAX_MESSAGES, Integer.MAX_VALUE),
                Duration.ofMillis(number(env, "IDLE_TIMEOUT_MS", DEFAULT_IDLE_TIMEOUT_MS, Long.MAX_VALUE)));
    }

    /** Everything except the password, for the start-up log line. */
    @Override
    public String toString() {
        return "host=" + host + ":" + port + " username=" + username + " queue=" + queue
                + " maxMessages=" + maxMessages + " idleTimeout=" + idleTimeout.toMillis() + "ms";
    }

    private static String text(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static int number(Map<String, String> env, String name, int defaultValue, int max) {
        return (int) number(env, name, (long) defaultValue, max);
    }

    private static long number(Map<String, String> env, String name, long defaultValue, long max) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.strip());
            if (parsed < 1 || parsed > max) {
                throw new IllegalArgumentException(name + " must be between 1 and " + max + ", was " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, was '" + value + "'", e);
        }
    }
}
