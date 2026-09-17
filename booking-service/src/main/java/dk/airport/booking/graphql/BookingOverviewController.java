package dk.airport.booking.graphql;

import dk.airport.booking.query.BookingOverview;
import dk.airport.booking.query.BookingQueryService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Query side of the booking API (CQRS, dev plan DP-28): both operations read the read model booking_overview through
 * {@link BookingQueryService} and never touch the write model. Mutations and the write-model queries are in
 * {@link BookingController}.
 *
 * <p>Login is required because an overview contains payment and baggage data, which payment-service and
 * baggage-service only give to PASSENGER/OPERATIONS. As for {@code bookingByReference}, the booking reference itself
 * is what a passenger needs to know to look a booking up (a family member can book for someone else), so a PASSENGER
 * is not limited to bookings made with their own e-mail here; {@code myBookings} is.
 */
@Controller
@Validated
public class BookingOverviewController {

    private final BookingQueryService queries;

    public BookingOverviewController(BookingQueryService queries) {
        this.queries = queries;
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public BookingOverview bookingOverview(@Argument @NotBlank String reference) {
        return queries.overview(reference).orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public List<BookingOverview> myBookings(Authentication authentication) {
        String email = authentication.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("email") : null;
        return email == null || email.isBlank() ? List.of() : queries.overviewsForPassenger(email);
    }
}
