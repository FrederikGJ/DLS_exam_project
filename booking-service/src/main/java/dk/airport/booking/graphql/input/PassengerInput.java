package dk.airport.booking.graphql.input;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PassengerInput(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{5,20}$", message = "passportNumber must be 5-20 alphanumeric characters") String passportNumber,
        @Past LocalDate dateOfBirth
) {}
