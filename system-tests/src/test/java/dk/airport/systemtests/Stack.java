package dk.airport.systemtests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The containers of the system test, on one Docker network so the services find each other by host name exactly as
 * in docker-compose and Kubernetes:
 * <pre>
 *   postgres         one postgres:16-alpine with flight_db, booking_db and payment_db (init.sql)
 *   rabbitmq         rabbitmq:3.13-management-alpine
 *   wiremock         stands in for Keycloak: the JWKS endpoint the services fetch the token signing key from
 *   flight-service   the real image airport/flight-service:local (seed data from its own Flyway migrations)
 *   booking-service  the real image airport/booking-service:local (calls flight-service synchronously)
 *   payment-service  the real image airport/payment-service:local
 * </pre>
 * Start order: infrastructure, then the JWKS stub, then the three services in parallel. booking-service only calls
 * flight-service on the first booking and the JWKS is fetched lazily on the first token, so no service waits for
 * another at boot.
 */
final class Stack implements AutoCloseable {

    static final String FLIGHT_IMAGE = "airport/flight-service:local";
    static final String BOOKING_IMAGE = "airport/booking-service:local";
    static final String PAYMENT_IMAGE = "airport/payment-service:local";
    static final List<String> IMAGES = List.of(FLIGHT_IMAGE, BOOKING_IMAGE, PAYMENT_IMAGE);
    static final String EXCHANGE = "airport.events";
    static final String FLIGHT_GRAPHQL_PATH = "/api/flights/graphql";
    static final String BOOKING_GRAPHQL_PATH = "/api/bookings/graphql";
    static final String PAYMENT_GRAPHQL_PATH = "/api/payments/graphql";
    static final String JWKS_PATH = "/realms/airport/protocol/openid-connect/certs";

    private static final Logger log = LoggerFactory.getLogger(Stack.class);
    private static final int SERVICE_PORT = 8080;
    private static final int WIREMOCK_PORT = 8080;
    private static final Duration SERVICE_STARTUP = Duration.ofMinutes(3);
    /** A modest limit per service; with MaxRAMPercentage=75 the JVM sizes its heap from it. */
    private static final long SERVICE_MEMORY_BYTES = 640L * 1024 * 1024;
    private static final String JAVA_TOOL_OPTIONS =
            "-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k";

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer<?> postgres;
    private final RabbitMQContainer rabbit;
    private final GenericContainer<?> wiremock;
    private final GenericContainer<?> flight;
    private final GenericContainer<?> booking;
    private final GenericContainer<?> payment;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    Stack() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withCopyFileToContainer(MountableFile.forClasspathResource("init.sql"),
                        "/docker-entrypoint-initdb.d/init.sql");
        rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
                .withNetwork(network)
                .withNetworkAliases("rabbitmq");
        wiremock = new GenericContainer<>("wiremock/wiremock:3.13.2-alpine")
                .withNetwork(network)
                .withNetworkAliases("wiremock")
                .withExposedPorts(WIREMOCK_PORT)
                .waitingFor(Wait.forHttp("/__admin/mappings").forPort(WIREMOCK_PORT).forStatusCode(200));
        flight = service(FLIGHT_IMAGE, "flight-service", Map.of(
                "DB_URL", "jdbc:postgresql://postgres:5432/flight_db",
                "DB_USERNAME", "flight",
                "DB_PASSWORD", "flight",
                "GRAPHQL_PATH", FLIGHT_GRAPHQL_PATH));
        booking = service(BOOKING_IMAGE, "booking-service", Map.of(
                "DB_URL", "jdbc:postgresql://postgres:5432/booking_db",
                "DB_USERNAME", "booking",
                "DB_PASSWORD", "booking",
                "GRAPHQL_PATH", BOOKING_GRAPHQL_PATH,
                "FLIGHT_SERVICE_URL", "http://flight-service:" + SERVICE_PORT + FLIGHT_GRAPHQL_PATH));
        payment = service(PAYMENT_IMAGE, "payment-service", Map.of(
                "DB_URL", "jdbc:postgresql://postgres:5432/payment_db",
                "DB_USERNAME", "payment",
                "DB_PASSWORD", "payment",
                "GRAPHQL_PATH", PAYMENT_GRAPHQL_PATH));
    }

    /** The images that are not built locally ({@code docker compose build}); empty when all three exist. */
    static List<String> missingImages() {
        return IMAGES.stream().filter(image -> {
            try {
                DockerClientFactory.instance().client().inspectImageCmd(image).exec();
                return false;
            } catch (NotFoundException e) {
                return true;
            }
        }).toList();
    }

    void start() {
        Startables.deepStart(postgres, rabbit, wiremock).join();
        registerJwksStub();
        Startables.deepStart(flight, booking, payment).join();
        log.info("Stack ready: flight-service {} booking-service {} payment-service {}",
                flightUrl(), bookingUrl(), paymentUrl());
    }

    String flightUrl() {
        return url(flight, FLIGHT_GRAPHQL_PATH);
    }

    String bookingUrl() {
        return url(booking, BOOKING_GRAPHQL_PATH);
    }

    String paymentUrl() {
        return url(payment, PAYMENT_GRAPHQL_PATH);
    }

    String rabbitHost() {
        return rabbit.getHost();
    }

    int rabbitPort() {
        return rabbit.getAmqpPort();
    }

    String rabbitUsername() {
        return rabbit.getAdminUsername();
    }

    String rabbitPassword() {
        return rabbit.getAdminPassword();
    }

    @Override
    public void close() {
        flight.stop();
        booking.stop();
        payment.stop();
        wiremock.stop();
        rabbit.stop();
        postgres.stop();
        network.close();
    }

    // ------------------------------------------------------------------ containers

    private static String url(GenericContainer<?> container, String path) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(SERVICE_PORT) + path;
    }

    private GenericContainer<?> service(String image, String alias, Map<String, String> serviceEnv) {
        Map<String, String> env = new HashMap<>(Map.of(
                "SPRING_PROFILES_ACTIVE", "dev",
                "RABBITMQ_HOST", "rabbitmq",
                "RABBITMQ_PORT", "5672",
                "RABBITMQ_USERNAME", rabbit.getAdminUsername(),
                "RABBITMQ_PASSWORD", rabbit.getAdminPassword(),
                "OIDC_ISSUER_URI", TestKeys.ISSUER,
                "JWK_SET_URI", "http://wiremock:" + WIREMOCK_PORT + JWKS_PATH,
                "JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS));
        env.putAll(serviceEnv);
        return new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withEnv(env)
                .withExposedPorts(SERVICE_PORT)
                .withCreateContainerCmdModifier(cmd -> {
                    HostConfig hostConfig = cmd.getHostConfig();
                    if (hostConfig != null) {
                        hostConfig.withMemory(SERVICE_MEMORY_BYTES);
                    }
                })
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container." + alias)))
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(SERVICE_PORT).forStatusCode(200)
                        .withStartupTimeout(SERVICE_STARTUP));
    }

    // ------------------------------------------------------------------ WireMock stub

    /** Keycloak's JWKS endpoint: the public half of the test key, so the services accept tokens from TestKeys. */
    private void registerJwksStub() {
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.putObject("request").put("method", "GET").put("urlPath", JWKS_PATH);
        ObjectNode response = mapping.putObject("response");
        response.put("status", 200);
        response.putObject("headers").put("Content-Type", "application/json");
        response.put("body", TestKeys.jwks());

        String adminUrl = "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(WIREMOCK_PORT)
                + "/__admin/mappings";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(adminUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mapping)))
                    .build();
            HttpResponse<String> answer = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() != 201) {
                throw new IllegalStateException("WireMock rejected the stub (" + answer.statusCode() + "): "
                        + answer.body());
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise WireMock mapping", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reach WireMock admin API at " + adminUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while registering the WireMock stub", e);
        }
    }
}
