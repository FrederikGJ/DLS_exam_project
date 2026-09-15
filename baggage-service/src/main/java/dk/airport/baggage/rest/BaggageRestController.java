package dk.airport.baggage.rest;

import dk.airport.baggage.domain.ApiException;
import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.rest.dto.BaggageResponse;
import dk.airport.baggage.rest.dto.RegisterBaggageRequest;
import dk.airport.baggage.rest.dto.UpdateBaggageStatusRequest;
import dk.airport.baggage.service.BaggageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * REST API v1 of baggage-service (dev plan DP-09) - the same operations as the GraphQL API, for clients that
 * prefer plain HTTP: register a bag, look it up, list the bags of a booking, move a bag to a new status.
 *
 * <p>Versioning: the version is part of the path ({@code /api/baggage/v1}), so a breaking change becomes
 * {@code /api/baggage/v2} next to it while v1 keeps working. Errors are RFC 9457 problem details
 * ({@code application/problem+json}) with the same {@code code} values as the GraphQL API - see
 * {@link RestExceptionHandler}. Who may call what is decided by URL in {@code config/SecurityConfig}: the bag
 * lookup is public, registration and the booking list need PASSENGER or OPERATIONS, the status change OPERATIONS.
 */
@RestController
@RequestMapping(path = BaggageRestController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class BaggageRestController {

    public static final String BASE_PATH = "/api/baggage/v1";
    private static final String REFERENCE_PATTERN = "^[A-Za-z0-9]{6}$";

    private final BaggageService baggageService;

    public BaggageRestController(BaggageService baggageService) {
        this.baggageService = baggageService;
    }

    /** 201 with {@code Location: /api/baggage/v1/baggage/{tagNumber}} and the new bag as body. */
    @PostMapping(path = "/baggage", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BaggageResponse> register(@Valid @RequestBody RegisterBaggageRequest request) {
        Baggage bag = baggageService.register(request.bookingReference(), request.weightKg(), request.type());
        // Relative Location on purpose: behind the Ingress the absolute scheme/host/port seen by the pod is not
        // the one the client used (X-Forwarded-Port is 80, not 8090), and a relative reference is resolved by the
        // client against the URL it just called.
        URI location = URI.create(BASE_PATH + "/baggage/" + bag.getTagNumber());
        return ResponseEntity.created(location).body(BaggageResponse.from(bag));
    }

    @GetMapping("/baggage/{tagNumber}")
    public BaggageResponse byTag(@PathVariable @NotBlank String tagNumber) {
        return baggageService.byTag(tagNumber).map(BaggageResponse::from)
                .orElseThrow(() -> ApiException.notFound("Baggage", tagNumber));
    }

    @GetMapping("/bookings/{reference}/baggage")
    public List<BaggageResponse> byBooking(
            @PathVariable @Pattern(regexp = REFERENCE_PATTERN, message = "must be 6 alphanumeric characters")
            String reference) {
        return baggageService.byBooking(reference).stream().map(BaggageResponse::from).toList();
    }

    @PatchMapping(path = "/baggage/{tagNumber}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BaggageResponse updateStatus(@PathVariable @NotBlank String tagNumber,
                                        @Valid @RequestBody UpdateBaggageStatusRequest request) {
        return BaggageResponse.from(baggageService.updateStatus(tagNumber, request.status(), request.location()));
    }
}
