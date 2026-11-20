package dk.airport.booking.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dk.airport.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record BookingOverview(Details booking, String latestPaymentStatus, int baggageCount,
                              List<Payment> payments, List<Baggage> baggage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Details(Long id, String bookingReference, Passenger passenger, Long flightId, String flightNumber,
                          OffsetDateTime departureTime, String gate, String flightStatus, String seatNumber,
                          BigDecimal price, String currency, BookingStatus status, OffsetDateTime createdAt,
                          OffsetDateTime updatedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Passenger(Long id, String firstName, String lastName, String email, String passportNumber,
                            LocalDate dateOfBirth) {}

    public record Payment(Long paymentId, String status, BigDecimal amount, String currency, String cardLast4,
                          String failureReason, OffsetDateTime createdAt, OffsetDateTime statusAt) {}

    public record Baggage(String tagNumber, String status, String type, BigDecimal weightKg, String lastLocation,
                          OffsetDateTime registeredAt, OffsetDateTime updatedAt) {}
}
