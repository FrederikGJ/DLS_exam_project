package dk.airport.flight.graphql;

import dk.airport.flight.domain.*;
import dk.airport.flight.graphql.input.CreateAircraftInput;
import dk.airport.flight.graphql.input.CreateAirlineInput;
import dk.airport.flight.graphql.input.CreateFlightInput;
import dk.airport.flight.graphql.input.FlightFilter;
import dk.airport.flight.service.FlightService;
import dk.airport.flight.service.PricingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Controller
@Validated
public class FlightController {

    private final FlightService flightService;
    private final PricingService pricingService;

    public FlightController(FlightService flightService, PricingService pricingService) {
        this.flightService = flightService;
        this.pricingService = pricingService;
    }

    // ---------------------------------------------------------- queries

    @QueryMapping
    public List<Airline> airlines() {
        return flightService.airlines();
    }

    @QueryMapping
    public Airline airline(@Argument Long id) {
        return flightService.airline(id).orElse(null);
    }

    @QueryMapping
    public List<Flight> flights(@Argument FlightFilter filter) {
        return flightService.flights(filter);
    }

    @QueryMapping
    public Flight flight(@Argument Long id) {
        return flightService.flight(id).orElse(null);
    }

    @QueryMapping
    public Flight flightByNumber(@Argument @NotBlank String flightNumber, @Argument LocalDate date) {
        return flightService.flightByNumber(flightNumber, date).orElse(null);
    }

    @QueryMapping
    public List<Seat> availableSeats(@Argument Long flightId) {
        flightService.requireFlight(flightId);
        return flightService.seats(flightId, true);
    }

    // ---------------------------------------------------------- nested fields

    @SchemaMapping(typeName = "Airline")
    public List<Aircraft> aircraft(Airline airline) {
        return flightService.aircraftByAirline(airline.getId());
    }

    @SchemaMapping(typeName = "Airline")
    public List<Flight> flights(Airline airline) {
        return flightService.flightsByAirline(airline.getId());
    }

    @SchemaMapping(typeName = "Flight")
    public List<Seat> seats(Flight flight, @Argument Boolean onlyAvailable) {
        return flightService.seats(flight.getId(), Boolean.TRUE.equals(onlyAvailable));
    }

    @SchemaMapping(typeName = "Flight")
    public Seat seat(Flight flight, @Argument String seatNumber) {
        return flightService.seat(flight.getId(), seatNumber).orElse(null);
    }

    @SchemaMapping(typeName = "Flight")
    public int availableSeatCount(Flight flight) {
        return flightService.availableSeatCount(flight.getId());
    }

    @SchemaMapping(typeName = "Seat")
    public BigDecimal price(Seat seat) {
        return pricingService.seatPrice(seat.getFlight().getBasePrice(), seat.getSeatClass());
    }

    @SchemaMapping(typeName = "Seat", field = "isAvailable")
    public boolean isAvailable(Seat seat) {
        return seat.isAvailable();
    }

    // ---------------------------------------------------------- mutations

    @MutationMapping
    public Airline createAirline(@Argument @Valid CreateAirlineInput input) {
        return flightService.createAirline(input);
    }

    @MutationMapping
    public Aircraft createAircraft(@Argument @Valid CreateAircraftInput input) {
        return flightService.createAircraft(input);
    }

    @MutationMapping
    public Flight createFlight(@Argument @Valid CreateFlightInput input) {
        return flightService.createFlight(input);
    }

    @MutationMapping
    public Flight updateFlightStatus(@Argument Long flightId, @Argument FlightStatus status) {
        return flightService.updateStatus(flightId, status);
    }

    @MutationMapping
    public Flight updateGate(@Argument Long flightId, @Argument @NotBlank String gate) {
        return flightService.updateGate(flightId, gate);
    }
}
