package dk.airport.baggage.graphql;

import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.domain.BaggageStatus;
import dk.airport.baggage.domain.BaggageType;
import dk.airport.baggage.domain.BookingSnapshot;
import dk.airport.baggage.service.BaggageService;
import jakarta.validation.constraints.*;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

@Controller
@Validated
public class BaggageController {

    private final BaggageService baggageService;

    public BaggageController(BaggageService baggageService) {
        this.baggageService = baggageService;
    }

    // ---------------------------------------------------------- queries

    @QueryMapping
    public Baggage baggage(@Argument @NotBlank String tagNumber) {
        return baggageService.byTag(tagNumber).orElse(null);
    }

    @QueryMapping
    public List<Baggage> baggageByBooking(@Argument @NotBlank String reference) {
        return baggageService.byBooking(reference);
    }

    @QueryMapping
    public List<Baggage> baggageByFlight(@Argument @NotBlank String flightNumber) {
        return baggageService.byFlight(flightNumber);
    }

    @QueryMapping
    public BookingSnapshot bookingSnapshot(@Argument @NotBlank String reference) {
        return baggageService.snapshot(reference).orElse(null);
    }

    // ---------------------------------------------------------- mutations

    @MutationMapping
    public Baggage registerBaggage(
            @Argument @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6}$", message = "bookingReference must be 6 alphanumeric characters") String bookingReference,
            @Argument @NotNull @DecimalMin("0.1") @DecimalMax("32.0") @Digits(integer = 3, fraction = 2) BigDecimal weightKg,
            @Argument @NotNull BaggageType type) {
        return baggageService.register(bookingReference, weightKg, type);
    }

    @MutationMapping
    public Baggage updateBaggageStatus(@Argument @NotBlank String tagNumber,
                                       @Argument @NotNull BaggageStatus status,
                                       @Argument @Size(max = 100) String location) {
        return baggageService.updateStatus(tagNumber, status, location);
    }
}
