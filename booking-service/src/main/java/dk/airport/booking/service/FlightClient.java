package dk.airport.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import dk.airport.booking.domain.ApiException;
import dk.airport.booking.domain.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Synchronous service-to-service GraphQL call to flight-service.
 * Chosen over caching seat/price data from events, because it always gives the current price and
 * availability and keeps booking-service free of a copy of the whole seat map.
 */
@Component
public class FlightClient {

    private static final Logger log = LoggerFactory.getLogger(FlightClient.class);

    static final String QUERY = """
            query($id: ID!, $seat: String!) {
              flight(id: $id) {
                id flightNumber scheduledDeparture gate status currency
                seat(seatNumber: $seat) { seatNumber seatClass isAvailable price }
              }
            }""";

    private final RestClient restClient;

    public FlightClient(@Value("${app.flight-service.url}") String url,
                        @Value("${app.flight-service.timeout:5s}") Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Fetches flight + seat info. Throws ApiException with NOT_FOUND / INVALID_STATE / SEAT_TAKEN / UPSTREAM_UNAVAILABLE.
     */
    public FlightSeatInfo fetchFlightSeat(Long flightId, String seatNumber) {
        JsonNode response;
        try {
            response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", QUERY, "variables", Map.of("id", String.valueOf(flightId), "seat", seatNumber)))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.error("flight-service call failed: {}", e.getMessage());
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "flight-service unavailable");
        }
        if (response == null) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "flight-service unavailable");
        }
        if (response.has("errors") && response.get("errors").isArray() && !response.get("errors").isEmpty()) {
            log.error("flight-service returned GraphQL errors: {}", response.get("errors"));
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "flight-service returned an error");
        }
        JsonNode flight = response.path("data").path("flight");
        if (flight.isMissingNode() || flight.isNull()) {
            throw ApiException.notFound("Flight", flightId);
        }
        String status = flight.path("status").asText();
        if ("CANCELLED".equals(status) || "DEPARTED".equals(status)) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "Flight " + flight.path("flightNumber").asText() + " is " + status + " and cannot be booked");
        }
        JsonNode seat = flight.path("seat");
        if (seat.isMissingNode() || seat.isNull()) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "Seat " + seatNumber + " not found on flight " + flight.path("flightNumber").asText());
        }
        if (!seat.path("isAvailable").asBoolean()) {
            throw new ApiException(ErrorCode.SEAT_TAKEN, "Seat " + seatNumber + " is already taken");
        }
        return new FlightSeatInfo(
                flight.path("id").asLong(),
                flight.path("flightNumber").asText(),
                OffsetDateTime.parse(flight.path("scheduledDeparture").asText()),
                flight.hasNonNull("gate") ? flight.get("gate").asText() : null,
                status,
                flight.hasNonNull("currency") ? flight.get("currency").asText() : "DKK",
                seat.path("seatNumber").asText(),
                seat.path("seatClass").asText(),
                true,
                new BigDecimal(seat.path("price").asText()));
    }
}
