package dk.airport.booking.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** What booking-service needs to know from flight-service to create a booking. */
public record FlightSeatInfo(
        Long flightId,
        String flightNumber,
        OffsetDateTime scheduledDeparture,
        String gate,
        String flightStatus,
        String currency,
        String seatNumber,
        String seatClass,
        boolean seatAvailable,
        BigDecimal price
) {}
