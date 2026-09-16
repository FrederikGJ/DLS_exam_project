package dk.airport.baggage.rest;

import dk.airport.baggage.config.OpenApiConfig;
import dk.airport.baggage.domain.ApiException;
import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.rest.dto.BaggageResponse;
import dk.airport.baggage.rest.dto.RegisterBaggageRequest;
import dk.airport.baggage.rest.dto.UpdateBaggageStatusRequest;
import dk.airport.baggage.service.BaggageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

import static dk.airport.baggage.config.OpenApiConfig.PROBLEM_JSON;

/**
 * REST API v1 of baggage-service (dev plan DP-09) - the same operations as the GraphQL API, for clients that
 * prefer plain HTTP: register a bag, look it up, list the bags of a booking, move a bag to a new status.
 *
 * <p>Versioning: the version is part of the path ({@code /api/baggage/v1}), so a breaking change becomes
 * {@code /api/baggage/v2} next to it while v1 keeps working. Errors are RFC 9457 problem details
 * ({@code application/problem+json}) with the same {@code code} values as the GraphQL API - see
 * {@link RestExceptionHandler}. Who may call what is decided by URL in {@code config/SecurityConfig}: the bag
 * lookup is public, registration and the booking list need PASSENGER or OPERATIONS, the status change OPERATIONS.
 *
 * <p>The {@code @Operation}/{@code @ApiResponse} annotations feed the OpenAPI document (DP-10); see
 * {@link OpenApiConfig} for the URLs of the document and Swagger UI.
 */
@RestController
@RequestMapping(path = BaggageRestController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Baggage")
@ApiResponse(responseCode = "400", description = "The request is malformed (`code`: VALIDATION_ERROR)",
        content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "500", description = "Unexpected error (`code`: INTERNAL_ERROR)",
        content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
public class BaggageRestController {

    public static final String BASE_PATH = "/api/baggage/v1";
    /** Request header with the client's idempotency key for a registration (DP-30). */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    /** Response header telling whether a registration was a replay of an earlier request with the same key. */
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private static final String REFERENCE_PATTERN = "^[A-Za-z0-9]{6}$";

    private final BaggageService baggageService;

    public BaggageRestController(BaggageService baggageService) {
        this.baggageService = baggageService;
    }

    /** 201 with {@code Location: /api/baggage/v1/baggage/{tagNumber}} and the new bag as body. */
    @PostMapping(path = "/baggage", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = OpenApiConfig.KEYCLOAK_SCHEME)
    @Operation(summary = "Register a bag on a booking",
            description = "The booking must be known to baggage-service (a booking.confirmed event) and be "
                    + "CONFIRMED or CHECKED_IN. Max 3 CHECKED bags per booking, max 32 kg per bag. "
                    + "Publishes the event `baggage.registered`. Requires PASSENGER or OPERATIONS.")
    @ApiResponse(responseCode = "201", description = "The bag was registered (or, with a repeated "
            + "Idempotency-Key, the bag the first request registered)",
            headers = {
                @Header(name = "Location", description = "URL of the new bag", schema = @Schema(type = "string")),
                @Header(name = IDEMPOTENT_REPLAYED, description = "`true` when an earlier request with the same "
                        + "Idempotency-Key registered the bag and nothing new was created",
                        schema = @Schema(type = "boolean"))
            })
    @ApiResponse(responseCode = "401", description = "No or invalid token (`code`: UNAUTHORIZED)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The role does not allow this (`code`: FORBIDDEN)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown booking (`code`: NOT_FOUND)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The booking is not paid for (`code`: INVALID_STATE), or the "
            + "Idempotency-Key was already used for another registration (`code`: CONFLICT)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "A business rule says no (`code`: VALIDATION_ERROR / BAGGAGE_LIMIT_EXCEEDED)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<BaggageResponse> register(
            @Valid @RequestBody RegisterBaggageRequest request,
            @Parameter(description = "Optional key for this one registration, e.g. a UUID per form (max 64 "
                    + "characters). A repeated request with the same key - double click, retry after a timeout - "
                    + "answers with the bag the first request registered instead of registering a second one.",
                    example = "3f1c2a8e-8d0e-4f3c-9a6b-1d2e3f4a5b6c")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
            @Size(max = BaggageService.MAX_IDEMPOTENCY_KEY, message = "must be at most 64 characters")
            String idempotencyKey) {
        BaggageService.Registration registration = baggageService.register(request.bookingReference(),
                request.weightKg(), request.type(), idempotencyKey);
        Baggage bag = registration.baggage();
        // Relative Location on purpose: behind the Ingress the absolute scheme/host/port seen by the pod is not
        // the one the client used (X-Forwarded-Port is 80, not 8090), and a relative reference is resolved by the
        // client against the URL it just called.
        URI location = URI.create(BASE_PATH + "/baggage/" + bag.getTagNumber());
        // A replay answers exactly like the first request (201, same Location and body), so a client that retries
        // after a lost response cannot tell the difference - except through this header.
        return ResponseEntity.created(location)
                .header(IDEMPOTENT_REPLAYED, String.valueOf(registration.replayed()))
                .body(BaggageResponse.from(bag));
    }

    @GetMapping("/baggage/{tagNumber}")
    @Operation(summary = "Look up a bag by its tag number",
            description = "Public, like the GraphQL query `baggage`: a passenger can follow a bag without logging "
                    + "in, and the tag number itself is the secret.")
    @ApiResponse(responseCode = "200", description = "The bag")
    @ApiResponse(responseCode = "404", description = "No bag with that tag (`code`: NOT_FOUND)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public BaggageResponse byTag(
            @Parameter(description = "Tag number, e.g. BAG-7KQ2ZP14", example = "BAG-7KQ2ZP14")
            @PathVariable @NotBlank String tagNumber) {
        return baggageService.byTag(tagNumber).map(BaggageResponse::from)
                .orElseThrow(() -> ApiException.notFound("Baggage", tagNumber));
    }

    @GetMapping("/bookings/{reference}/baggage")
    @SecurityRequirement(name = OpenApiConfig.KEYCLOAK_SCHEME)
    @Operation(summary = "All bags of a booking",
            description = "Ordered by registration time. Requires PASSENGER or OPERATIONS.")
    @ApiResponse(responseCode = "200", description = "The bags of the booking (an empty list if it has none)")
    @ApiResponse(responseCode = "401", description = "No or invalid token (`code`: UNAUTHORIZED)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The role does not allow this (`code`: FORBIDDEN)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public List<BaggageResponse> byBooking(
            @Parameter(description = "Booking reference, 6 alphanumeric characters", example = "K7Q2ZP")
            @PathVariable @Pattern(regexp = REFERENCE_PATTERN, message = "must be 6 alphanumeric characters")
            String reference) {
        return baggageService.byBooking(reference).stream().map(BaggageResponse::from).toList();
    }

    @PatchMapping(path = "/baggage/{tagNumber}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = OpenApiConfig.KEYCLOAK_SCHEME)
    @Operation(summary = "Move a bag to a new status",
            description = "Ground staff moving a bag through the airport (REGISTERED -> SECURITY -> LOADED -> "
                    + "IN_TRANSIT -> ARRIVED, or LOST). A missing `location` keeps the previous one. "
                    + "Publishes the event `baggage.status.changed`. Requires OPERATIONS.")
    @ApiResponse(responseCode = "200", description = "The updated bag")
    @ApiResponse(responseCode = "401", description = "No or invalid token (`code`: UNAUTHORIZED)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Not an OPERATIONS user (`code`: FORBIDDEN)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No bag with that tag (`code`: NOT_FOUND)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public BaggageResponse updateStatus(
            @Parameter(description = "Tag number of the bag", example = "BAG-7KQ2ZP14")
            @PathVariable @NotBlank String tagNumber,
            @Valid @RequestBody UpdateBaggageStatusRequest request) {
        return BaggageResponse.from(baggageService.updateStatus(tagNumber, request.status(), request.location()));
    }
}
