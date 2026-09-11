package dk.airport.flight.graphql.input;

import jakarta.validation.constraints.*;

public record CreateAircraftInput(
        @NotBlank @Size(max = 20) String registration,
        @NotBlank @Size(max = 100) String model,
        @NotNull @Min(1) @Max(1000) Integer totalSeats,
        @NotNull Long airlineId
) {}
