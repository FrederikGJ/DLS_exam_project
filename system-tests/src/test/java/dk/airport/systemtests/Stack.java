package dk.airport.systemtests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The containers of the system test, on one Docker network so the services find each other by host name exactly as
 * in docker-compose and Kubernetes:
 * <pre>
 *   postgres         one postgres:16-alpine with booking_db and payment_db (init.sql)
 *   rabbitmq         rabbitmq:3.13-management-alpine
 *   wiremock         stands in for flight-service (the GraphQL query booking-service makes) and for Keycloak
 *                    (the JWKS endpoint both services fetch the token signing key from)
 *   booking-service  the real image airport/booking-service:local
 *   payment-service  the real image airport/payment-service:local
 * </pre>
 * Start order: infrastructure, then the WireMock stubs, then both services in parallel. Neither service needs
 * WireMock at boot (the JWKS is fetched lazily on the first token, flight-service on the first booking), but
 * registering the stubs before the services start keeps the sequence obvious.
 */
final class Stack implements AutoCloseable {

    static final String BOOKING_IMAGE = "airport/booking-service:local";
    static final String PAYMENT_IMAGE = "airport/payment-service:local";
    static final String EXCHANGE = "airport.events";
    static final String BOOKING_GRAPHQL_PATH = "/api/bookings/graphql";
    static final String PAYMENT_GRAPHQL_PATH = "/api/payments/graphql";
    static final String FLIGHT_GRAPHQL_PATH = "/api/flights/graphql";
    static final String JWKS_PATH = "/realms/airport/protocol/openid-connect/certs";

    /** The one flight the stubbed flight-service knows; every seat on it is available at this price. */
    static final String FLIGHT_ID = "1";
    static final String FLIGHT_NUMBER = "SK1501";
    static final String SEAT_PRICE = "1299.00";

    private static final Logger log = LoggerFactory.getLogger(Stack.class);
    private static final int SERVICE_PORT = 8080;
    private static final int WIREMOCK_PORT = 8080;
    private static final Duration SERVICE_STARTUP = Duration.ofMinutes(3);
    /** Same limit as k8s/services/*.yaml; with MaxRAMPercentage=75 the JVM sizes its heap from it. */
    private static final long SERVICE_MEMORY_BYTES = 640L * 1024 * 1024;
    private static final String JAVA_TOOL_OPTIONS =
            "-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k";

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer<?> postgres;
    private final RabbitMQContainer rabbit;
    private final GenericContainer<?> wiremock;
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
        // --disable-http2-plain: booking-service's FlightClient (Spring RestClient on the JDK HttpClient) sends its
        // first request with "Upgrade: h2c" and a chunked body; WireMock's Jetty drops that connection ("EOF reached
        // while reading"). The real flight-service (Tomcat) answers HTTP/1.1 and is not affected.
        wiremock = new GenericContainer<>("wiremock/wiremock:3.13.2-alpine")
                .withNetwork(network)
                .withNetworkAliases("wiremock")
                .withCommand("--disable-http2-plain")
                .withExposedPorts(WIREMOCK_PORT)
                .waitingFor(Wait.forHttp("/__admin/mappings").forPort(WIREMOCK_PORT).forStatusCode(200));
        booking = service(BOOKING_IMAGE, "booking-service", Map.of(
                "DB_URL", "jdbc:postgresql://postgres:5432/booking_db",
                "DB_USERNAME", "booking",
                "DB_PASSWORD", "booking",
                "GRAPHQL_PATH", BOOKING_GRAPHQL_PATH,
                "FLIGHT_SERVICE_URL", "http://wiremock:" + WIREMOCK_PORT + FLIGHT_GRAPHQL_PATH));
        payment = service(PAYMENT_IMAGE, "payment-service", Map.of(
                "DB_URL", "jdbc:postgresql://postgres:5432/payment_db",
                "DB_USERNAME", "payment",
                "DB_PASSWORD", "payment",
                "GRAPHQL_PATH", PAYMENT_GRAPHQL_PATH));
    }

    /** True when Docker is reachable and both service images exist locally ({@code docker compose build}). */
    static boolean imagesPresent() {
        DockerClientFactory docker = DockerClientFactory.instance();
        if (!docker.isDockerAvailable()) {
            log.warn("Docker is not available - the system test needs it");
            return false;
        }
        for (String image : List.of(BOOKING_IMAGE, PAYMENT_IMAGE)) {
            try {
                docker.client().inspectImageCmd(image).exec();
            } catch (NotFoundException e) {
                log.warn("Image {} is not built - run `docker compose build` in the repository root first", image);
                return false;
            }
        }
        return true;
    }

    void start() {
        Startables.deepStart(postgres, rabbit, wiremock).join();
        registerFlightServiceStub();
        registerJwksStub();
        Startables.deepStart(booking, payment).join();
        log.info("Stack ready: booking-service {} payment-service {}", bookingUrl(), paymentUrl());
    }

    String bookingUrl() {
        return "http://" + booking.getHost() + ":" + booking.getMappedPort(SERVICE_PORT) + BOOKING_GRAPHQL_PATH;
    }

    String paymentUrl() {
        return "http://" + payment.getHost() + ":" + payment.getMappedPort(SERVICE_PORT) + PAYMENT_GRAPHQL_PATH;
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
        booking.stop();
        payment.stop();
        wiremock.stop();
        rabbit.stop();
        postgres.stop();
        network.close();
    }

    // ------------------------------------------------------------------ containers

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

    // ------------------------------------------------------------------ WireMock stubs

    /**
     * flight-service as booking-service sees it: the one query in {@code FlightClient} (flight + seat by number).
     * Flight 1 exists with every seat available; the requested seat number is echoed back with response templating
     * so each test can book its own seat. Any other flight id answers {@code flight: null}, which booking-service
     * turns into NOT_FOUND.
     */
    private void registerFlightServiceStub() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode flight = root.putObject("data").putObject("flight");
        flight.put("id", FLIGHT_ID)
                .put("flightNumber", FLIGHT_NUMBER)
                .put("scheduledDeparture", OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0).toString())
                .put("gate", "A12")
                .put("status", "SCHEDULED")
                .put("currency", "DKK");
        flight.putObject("seat")
                .put("seatNumber", "{{jsonPath request.body '$.variables.seat'}}")
                .put("seatClass", "ECONOMY")
                .put("isAvailable", true)
                .put("price", new BigDecimal(SEAT_PRICE));

        ObjectNode known = mapping(1, "POST", FLIGHT_GRAPHQL_PATH, json(root));
        ArrayNode bodyPatterns = known.withObject("request").putArray("bodyPatterns");
        bodyPatterns.addObject().putObject("matchesJsonPath")
                .put("expression", "$.query").put("contains", "seat(seatNumber: $seat)");
        bodyPatterns.addObject().putObject("matchesJsonPath")
                .put("expression", "$.variables.id").put("equalTo", FLIGHT_ID);
        known.withObject("response").putArray("transformers").add("response-template");
        register(known);

        register(mapping(10, "POST", FLIGHT_GRAPHQL_PATH, "{\"data\": {\"flight\": null}}"));
    }

    /** Keycloak's JWKS endpoint: the public half of the test key, so the services accept tokens from TestKeys. */
    private void registerJwksStub() {
        register(mapping(1, "GET", JWKS_PATH, TestKeys.jwks()));
    }

    private String json(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise stub body", e);
        }
    }

    private ObjectNode mapping(int priority, String method, String path, String jsonBody) {
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.put("priority", priority);
        mapping.putObject("request").put("method", method).put("urlPath", path);
        ObjectNode response = mapping.putObject("response");
        response.put("status", 200);
        response.putObject("headers").put("Content-Type", "application/json");
        response.put("body", jsonBody);
        return mapping;
    }

    private void register(ObjectNode mapping) {
        String adminUrl = "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(WIREMOCK_PORT)
                + "/__admin/mappings";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(adminUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mapping)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException("WireMock rejected the stub (" + response.statusCode() + "): "
                        + response.body());
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise WireMock mapping", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reach WireMock admin API at " + adminUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while registering a WireMock stub", e);
        }
    }
}
