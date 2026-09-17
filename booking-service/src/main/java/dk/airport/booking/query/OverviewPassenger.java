package dk.airport.booking.query;

import java.time.LocalDate;

/** The passenger part of a {@link BookingOverview} (GraphQL type OverviewPassenger). */
public record OverviewPassenger(String firstName, String lastName, String email, String passportNumber,
                                LocalDate dateOfBirth) {}
