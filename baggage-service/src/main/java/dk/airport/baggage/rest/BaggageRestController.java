package dk.airport.baggage.rest;

import dk.airport.baggage.rest.dto.BaggageResponse;
import dk.airport.baggage.rest.dto.RegisterBaggageRequest;
import dk.airport.baggage.rest.dto.UpdateBaggageStatusRequest;
import dk.airport.baggage.service.BaggageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = BaggageRestController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
public class BaggageRestController {

    public static final String BASE_PATH = "/api/baggage/v1";

    private final BaggageService baggageService;

    public BaggageRestController(BaggageService baggageService) {
        this.baggageService = baggageService;
    }

    @GetMapping("/bookings/{ref}/baggage")
    @Operation(summary = "List bags for a booking", description = "Requires PASSENGER or OPERATIONS.")
    public List<BaggageResponse> byBooking(@PathVariable @NotBlank String ref) {
        return baggageService.byBooking(ref).stream().map(BaggageResponse::from).toList();
    }

    @PostMapping(path = "/baggage", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a bag", description = "Requires PASSENGER or OPERATIONS.")
    @ApiResponse(responseCode = "201", description = "Bag registered", useReturnTypeSchema = true)
    public ResponseEntity<BaggageResponse> register(@Valid @RequestBody RegisterBaggageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(BaggageResponse.from(
                baggageService.register(request.bookingReference(), request.weightKg(), request.type())));
    }

    @PatchMapping(path = "/baggage/{tag}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update the status of a bag", description = "Requires OPERATIONS.")
    public BaggageResponse updateStatus(@PathVariable @NotBlank String tag,
                                       @Valid @RequestBody UpdateBaggageStatusRequest request) {
        return BaggageResponse.from(baggageService.updateStatus(tag, request.status(), request.location()));
    }
}
