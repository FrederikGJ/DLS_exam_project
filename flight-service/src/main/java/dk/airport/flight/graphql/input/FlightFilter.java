package dk.airport.flight.graphql.input;

import dk.airport.flight.domain.FlightStatus;

import java.time.LocalDate;

public record FlightFilter(String destination, LocalDate date, FlightStatus status) {}
