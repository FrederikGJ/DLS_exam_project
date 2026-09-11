package dk.airport.flight.graphql.input;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAirlineInput(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{2,3}$", message = "iataCode must be 2-3 alphanumeric characters") String iataCode,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String country
) {}
