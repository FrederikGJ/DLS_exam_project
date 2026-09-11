package dk.airport.booking.graphql;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.Passenger;
import dk.airport.booking.graphql.input.PassengerInput;
import dk.airport.booking.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Controller
@Validated
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // ---------------------------------------------------------- queries

    @QueryMapping
    public Booking booking(@Argument Long id) {
        return bookingService.booking(id).orElse(null);
    }

    @QueryMapping
    public Booking bookingByReference(@Argument @NotBlank String reference) {
        return bookingService.bookingByReference(reference).orElse(null);
    }

    @QueryMapping
    public List<Booking> bookingsByPassenger(@Argument @NotBlank String email) {
        return bookingService.bookingsByPassenger(email);
    }

    @QueryMapping
    public Passenger passenger(@Argument Long id) {
        return bookingService.passenger(id).orElse(null);
    }

    // ---------------------------------------------------------- nested fields

    @SchemaMapping(typeName = "Passenger")
    public List<Booking> bookings(Passenger passenger) {
        return bookingService.bookingsByPassengerId(passenger.getId());
    }

    // ---------------------------------------------------------- mutations

    @MutationMapping
    public Booking createBooking(@Argument @NotNull Long flightId,
                                 @Argument @NotBlank @Pattern(regexp = "^[0-9]{1,3}[A-Za-z]$", message = "seatNumber must look like 12C") String seatNumber,
                                 @Argument @Valid PassengerInput passenger) {
        return bookingService.createBooking(flightId, seatNumber, passenger);
    }

    @MutationMapping
    public Booking cancelBooking(@Argument @NotBlank String reference) {
        return bookingService.cancelBooking(reference);
    }

    @MutationMapping
    public Booking checkIn(@Argument @NotBlank String reference) {
        return bookingService.checkIn(reference);
    }
}
