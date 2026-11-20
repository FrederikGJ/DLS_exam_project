package dk.airport.booking.graphql;

import dk.airport.booking.query.BookingOverview;
import dk.airport.booking.query.BookingQueryService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

@Controller
@Validated
public class BookingOverviewController {

    private final BookingQueryService queries;

    public BookingOverviewController(BookingQueryService queries) {
        this.queries = queries;
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")
    public BookingOverview bookingOverview(@Argument @NotBlank String reference, Authentication authentication) {
        BookingOverview overview = queries.bookingOverview(reference).orElse(null);
        if (overview == null) {
            return null;
        }
        boolean operations = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_OPERATIONS"));
        String email = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaimAsString("email") : null;
        if (!operations && !overview.booking().passenger().email().equalsIgnoreCase(email)) {
            throw new AccessDeniedException("Booking overview belongs to another passenger");
        }
        return overview;
    }
}
