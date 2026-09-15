package dk.airport.booking.graphql;

import dk.airport.booking.domain.ApiException;
import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.ErrorCode;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
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

    /**
     * A passenger may only list their own bookings: the {@code email} argument must match the e-mail claim of
     * the token (case-insensitive). OPERATIONS may list anyone's. Decided in dev plan DP-02: the argument stays
     * (it is part of the API), the token decides what it may be.
     */
    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public List<Booking> bookingsByPassenger(@Argument @NotBlank String email, Authentication authentication) {
        if (!hasRole(authentication, "OPERATIONS") && !email.equalsIgnoreCase(emailOf(authentication))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You can only list bookings for your own e-mail address");
        }
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
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking createBooking(
            @Argument @NotNull Long flightId,
            @Argument @NotBlank @Pattern(regexp = "^[0-9]{1,3}[A-Za-z]$", message = "seatNumber must look like 12C")
            String seatNumber,
            @Argument @Valid PassengerInput passenger) {
        return bookingService.createBooking(flightId, seatNumber, passenger);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking cancelBooking(@Argument @NotBlank String reference) {
        return bookingService.cancelBooking(reference);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking checkIn(@Argument @NotBlank String reference) {
        return bookingService.checkIn(reference);
    }

    // ---------------------------------------------------------- helpers

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    private static String emailOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("email") : null;
    }
}
