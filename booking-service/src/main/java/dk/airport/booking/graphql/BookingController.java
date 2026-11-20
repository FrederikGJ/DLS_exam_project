package dk.airport.booking.graphql;

import dk.airport.booking.domain.ApiException;
import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.ErrorCode;
import dk.airport.booking.domain.Passenger;
import dk.airport.booking.graphql.input.PassengerInput;
import dk.airport.booking.query.BookingQueryService;
import dk.airport.booking.repository.OffsetLimit;
import dk.airport.booking.service.BookingCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/**
 * Bookings hold personal data, so every operation requires a login (PASSENGER or OPERATIONS).
 *
 * <p>Ownership: a booking belongs to the passenger whose e-mail it was made with. A PASSENGER may only book with
 * the e-mail of their own token and only read, cancel and check in their own bookings; anything else is
 * {@code FORBIDDEN}. OPERATIONS may do all of it for every booking. The e-mail comes from the {@code email} claim
 * of the Keycloak token and is compared case-insensitively.
 */
@Controller
@Validated
public class BookingController {

    private final BookingCommandService commands;
    private final BookingQueryService queries;

    public BookingController(BookingCommandService commands, BookingQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    // ---------------------------------------------------------- queries

    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking booking(@Argument Long id, Authentication authentication) {
        return queries.booking(id).map(b -> requireOwner(b, authentication)).orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking bookingByReference(@Argument @NotBlank String reference, Authentication authentication) {
        return queries.bookingByReference(reference).map(b -> requireOwner(b, authentication)).orElse(null);
    }

    /**
     * A passenger may only list their own bookings: the {@code email} argument must match the e-mail claim of
     * the token. The argument stays (it is part of the API); the token decides what it may be.
     */
    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public List<Booking> bookingsByPassenger(@Argument @NotBlank String email,
                                             @Argument @Min(1) @Max(OffsetLimit.MAX_LIMIT) Integer limit,
                                             @Argument @Min(0) Integer offset,
                                             Authentication authentication) {
        requireOwnEmail(email, authentication, "You can only list bookings for your own e-mail address");
        return queries.bookingsByPassenger(email, offset, limit);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Passenger passenger(@Argument Long id, Authentication authentication) {
        return queries.passenger(id)
                .map(p -> {
                    requireOwnEmail(p.getEmail(), authentication, "You can only see your own passenger data");
                    return p;
                })
                .orElse(null);
    }

    // ---------------------------------------------------------- nested fields

    @SchemaMapping(typeName = "Passenger")
    public List<Booking> bookings(Passenger passenger) {
        return queries.bookingsByPassengerId(passenger.getId());
    }

    // ---------------------------------------------------------- mutations

    @MutationMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking createBooking(
            @Argument @NotNull Long flightId,
            @Argument @NotBlank @Pattern(regexp = "^[0-9]{1,3}[A-Za-z]$", message = "seatNumber must look like 12C")
            String seatNumber,
            @Argument @Valid PassengerInput passenger,
            Authentication authentication) {
        requireOwnEmail(passenger.email(), authentication, "Passengers can only book with their own e-mail address");
        return commands.createBooking(flightId, seatNumber, passenger);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking cancelBooking(@Argument @NotBlank String reference, Authentication authentication) {
        requireOwner(commands.requireBooking(reference), authentication);
        return commands.cancelBooking(reference);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public Booking checkIn(@Argument @NotBlank String reference, Authentication authentication) {
        requireOwner(commands.requireBooking(reference), authentication);
        return commands.checkIn(reference);
    }

    // ---------------------------------------------------------- helpers

    private static Booking requireOwner(Booking booking, Authentication authentication) {
        requireOwnEmail(booking.getPassenger().getEmail(), authentication, "This booking belongs to another passenger");
        return booking;
    }

    /** OPERATIONS passes; anyone else must carry a token whose e-mail equals {@code email}. */
    private static void requireOwnEmail(String email, Authentication authentication, String message) {
        if (hasRole(authentication, "OPERATIONS")) {
            return;
        }
        String own = emailOf(authentication);
        if (own == null || email == null || !email.trim().equalsIgnoreCase(own.trim())) {
            throw new ApiException(ErrorCode.FORBIDDEN, message);
        }
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    private static String emailOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("email") : null;
    }
}
