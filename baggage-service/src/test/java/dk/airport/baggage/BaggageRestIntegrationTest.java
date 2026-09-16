package dk.airport.baggage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.baggage.messaging.EventEnvelope;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API v1 (dev plan DP-12) against a real PostgreSQL + RabbitMQ (Testcontainers) and through the real security
 * filter chain: the status codes and {@code application/problem+json} bodies documented in README and
 * docs/architecture.md, and that a registration over REST publishes {@code baggage.registered} exactly as the
 * GraphQL mutation does. Tokens are minted by {@link TestTokens}, so no Keycloak is needed.
 *
 * <pre>
 *   POST   /api/baggage/v1/baggage                      PASSENGER/OPERATIONS  201 + Location
 *   GET    /api/baggage/v1/baggage/{tagNumber}          public                200 / 404
 *   GET    /api/baggage/v1/bookings/{ref}/baggage       PASSENGER/OPERATIONS  200
 *   PATCH  /api/baggage/v1/baggage/{tagNumber}/status   OPERATIONS            200 / 403
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestTokens.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BaggageRestIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final String BASE = "/api/baggage/v1";
    static final String TEST_QUEUE = "test.baggage-rest-events";
    /** A booking the passenger has paid for - baggage may be registered on it. */
    static final String CONFIRMED_REF = "RST001";
    /** A booking that is still PENDING_PAYMENT - registration must be refused with 409. */
    static final String UNPAID_REF = "RST002";
    static final String FLIGHT_NUMBER = "SK1501";

    /** Every event published on airport.events with routing key baggage.# ends up here. */
    static final List<EventEnvelope> RECEIVED = new CopyOnWriteArrayList<>();
    static SimpleMessageListenerContainer testListener;
    static ObjectMapper mapper;
    static RabbitTemplate publisher;
    /** Tag of the bag registered in the first test; the later tests look it up and change its status. */
    static String tag;

    @Autowired MockMvc mvc;

    @BeforeAll
    static void seedBookingsAndListenForEvents(@Autowired ConnectionFactory connectionFactory,
                                               @Autowired RabbitTemplate rabbitTemplate,
                                               @Autowired ObjectMapper objectMapper,
                                               @Autowired BookingSnapshotRepository snapshots) {
        mapper = objectMapper;
        publisher = rabbitTemplate;

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(TEST_QUEUE, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("airport.events")).with("baggage.#"));

        testListener = new SimpleMessageListenerContainer(connectionFactory);
        testListener.setQueueNames(TEST_QUEUE);
        testListener.setMessageListener(message -> {
            try {
                RECEIVED.add(mapper.readValue(message.getBody(), EventEnvelope.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        testListener.start();

        // baggage-service only knows the bookings it has heard about from booking-service
        publish("booking.confirmed", bookingPayload(CONFIRMED_REF, "CONFIRMED"));
        publish("booking.created", bookingPayload(UNPAID_REF, "PENDING_PAYMENT"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(snapshots.findById(CONFIRMED_REF)).isPresent();
            assertThat(snapshots.findById(UNPAID_REF)).isPresent();
        });
    }

    @AfterAll
    static void stopTestListener() {
        if (testListener != null) {
            testListener.stop();
        }
    }

    @Test
    @Order(1)
    void registerAnswers201WithLocationAndPublishesBaggageRegistered() throws Exception {
        MvcResult result = mvc.perform(post(BASE + "/baggage")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingReference": "%s", "weightKg": 20.5, "type": "CHECKED"}"""
                                .formatted(CONFIRMED_REF)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.bookingReference").value(CONFIRMED_REF))
                .andExpect(jsonPath("$.passengerName").value("Anna Jensen"))
                .andExpect(jsonPath("$.flightNumber").value(FLIGHT_NUMBER))
                .andExpect(jsonPath("$.lastLocation").value("CHECK_IN"))
                .andExpect(jsonPath("$.type").value("CHECKED"))
                .andReturn();

        tag = mapper.readTree(result.getResponse().getContentAsString()).path("tagNumber").asText();
        assertThat(tag).matches("^BAG-[A-Z0-9]{8}$");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(BASE + "/baggage/" + tag);

        // the REST endpoint goes through the same service + outbox as the GraphQL mutation
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(RECEIVED).anyMatch(e -> e.eventType().equals("baggage.registered")
                        && e.payload().path("tagNumber").asText().equals(tag)
                        && e.payload().path("bookingReference").asText().equals(CONFIRMED_REF)));
    }

    @Test
    @Order(2)
    void lookupIsPublicAndTheBookingListNeedsALogin() throws Exception {
        mvc.perform(get(BASE + "/baggage/" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagNumber").value(tag))
                .andExpect(jsonPath("$.bookingReference").value(CONFIRMED_REF));

        mvc.perform(get(BASE + "/bookings/" + CONFIRMED_REF + "/baggage")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tagNumber").value(tag));

        mvc.perform(get(BASE + "/bookings/" + CONFIRMED_REF + "/baggage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @Order(3)
    void unknownTagIsNotFoundAsProblemJson() throws Exception {
        mvc.perform(get(BASE + "/baggage/BAG-NOPE0000"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.instance").value(BASE + "/baggage/BAG-NOPE0000"));
    }

    @Test
    @Order(4)
    void businessRulesAnswer422And409() throws Exception {
        // well-formed request, but 40 kg breaks the 32 kg rule
        mvc.perform(register(CONFIRMED_REF, "40.0", "CHECKED"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // the booking exists but has not been paid for
        mvc.perform(register(UNPAID_REF, "18.0", "CHECKED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        // no such booking at all
        mvc.perform(register("ZZ9999", "18.0", "CHECKED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @Order(5)
    void malformedRequestsAreBadRequest() throws Exception {
        // bookingReference is not 6 alphanumeric characters
        mvc.perform(register("nope", "18.0", "CHECKED"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value(containsString("bookingReference")));

        // weightKg is missing
        mvc.perform(post(BASE + "/baggage")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingReference": "%s", "type": "CHECKED"}""".formatted(CONFIRMED_REF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // unknown enum value -> the body cannot be read at all
        mvc.perform(register(CONFIRMED_REF, "18.0", "SUITCASE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @Order(6)
    void registrationWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(post(BASE + "/baggage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingReference": "%s", "weightKg": 18.0, "type": "CABIN"}"""
                                .formatted(CONFIRMED_REF)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @Order(7)
    void onlyOperationsMayChangeTheStatus() throws Exception {
        String body = """
                {"status": "LOADED", "location": "Belt 4"}""";

        mvc.perform(patch(BASE + "/baggage/" + tag + "/status")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(patch(BASE + "/baggage/" + tag + "/status")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.operations())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOADED"))
                .andExpect(jsonPath("$.lastLocation").value("Belt 4"));

        mvc.perform(patch(BASE + "/baggage/BAG-NOPE0000/status")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.operations())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @Order(8)
    void openApiDocumentIsPublicAndDescribesTheFourEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Airport baggage API"))
                .andExpect(jsonPath("$.info.version").value("v1"))
                .andExpect(jsonPath("$.paths['" + BASE + "/baggage'].post").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/baggage/{tagNumber}'].get").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/bookings/{reference}/baggage'].get").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/baggage/{tagNumber}/status'].patch").exists())
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.scheme").value("bearer"));
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder register(String reference, String weightKg, String type) {
        return post(BASE + "/baggage")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.passenger())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bookingReference": "%s", "weightKg": %s, "type": "%s"}"""
                        .formatted(reference, weightKg, type));
    }

    private static Map<String, Object> bookingPayload(String reference, String status) {
        return Map.of(
                "bookingId", reference.hashCode() & 0xffff, "bookingReference", reference,
                "flightId", 1, "flightNumber", FLIGHT_NUMBER, "departureTime", "2026-12-24T14:00:00Z",
                "seatNumber", "12C", "price", 899.00, "currency", "DKK",
                "status", status,
                "passenger", Map.of("firstName", "Anna", "lastName", "Jensen", "email", "anna@example.com"));
    }

    private static void publish(String type, Map<String, Object> payload) {
        try {
            EventEnvelope env = new EventEnvelope(UUID.randomUUID().toString(), type, OffsetDateTime.now(),
                    "booking-service", mapper.valueToTree(payload));
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            publisher.send("airport.events", type, new Message(mapper.writeValueAsBytes(env), props));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
