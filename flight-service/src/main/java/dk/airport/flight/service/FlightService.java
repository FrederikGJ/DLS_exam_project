package dk.airport.flight.service;

import dk.airport.flight.domain.*;
import dk.airport.flight.graphql.input.CreateAircraftInput;
import dk.airport.flight.graphql.input.CreateAirlineInput;
import dk.airport.flight.graphql.input.CreateFlightInput;
import dk.airport.flight.graphql.input.FlightFilter;
import dk.airport.flight.messaging.EventPublisher;
import dk.airport.flight.messaging.FlightEvents;
import dk.airport.flight.repository.AircraftRepository;
import dk.airport.flight.repository.AirlineRepository;
import dk.airport.flight.repository.FlightRepository;
import dk.airport.flight.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class FlightService {

    private static final Logger log = LoggerFactory.getLogger(FlightService.class);

    private final AirlineRepository airlines;
    private final AircraftRepository aircraft;
    private final FlightRepository flights;
    private final SeatRepository seats;
    private final EventPublisher events;

    public FlightService(AirlineRepository airlines, AircraftRepository aircraft, FlightRepository flights,
                         SeatRepository seats, EventPublisher events) {
        this.airlines = airlines;
        this.aircraft = aircraft;
        this.flights = flights;
        this.seats = seats;
        this.events = events;
    }

    // ------------------------------------------------------------ queries

    public List<Airline> airlines() {
        return airlines.findAll();
    }

    public Optional<Airline> airline(Long id) {
        return airlines.findById(id);
    }

    public List<Aircraft> aircraftByAirline(Long airlineId) {
        return aircraft.findByAirlineIdOrderByRegistration(airlineId);
    }

    public List<Flight> flightsByAirline(Long airlineId) {
        return flights.findByAirlineIdOrderByScheduledDeparture(airlineId);
    }

    public List<Flight> flights(FlightFilter filter) {
        Specification<Flight> spec = Specification.where(null);
        if (filter != null) {
            if (filter.destination() != null && !filter.destination().isBlank()) {
                String dest = filter.destination().trim().toUpperCase();
                spec = spec.and((root, q, cb) -> cb.equal(cb.upper(root.get("destination")), dest));
            }
            if (filter.status() != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), filter.status()));
            }
            if (filter.date() != null) {
                OffsetDateTime from = filter.date().atStartOfDay().atOffset(ZoneOffset.UTC);
                OffsetDateTime to = from.plusDays(1);
                spec = spec.and((root, q, cb) -> cb.and(
                        cb.greaterThanOrEqualTo(root.get("scheduledDeparture"), from),
                        cb.lessThan(root.get("scheduledDeparture"), to)));
            }
        }
        return flights.findAll(spec, org.springframework.data.domain.Sort.by("scheduledDeparture"));
    }

    public Optional<Flight> flight(Long id) {
        return flights.findById(id);
    }

    public Flight requireFlight(Long id) {
        return flights.findById(id).orElseThrow(() -> ApiException.notFound("Flight", id));
    }

    public Optional<Flight> flightByNumber(String flightNumber, LocalDate date) {
        OffsetDateTime from = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        return flights.findFirstByFlightNumberIgnoreCaseAndScheduledDepartureGreaterThanEqualAndScheduledDepartureLessThanOrderByScheduledDeparture(
                flightNumber.trim(), from, from.plusDays(1));
    }

    public List<Seat> seats(Long flightId, boolean onlyAvailable) {
        return onlyAvailable ? seats.findAvailableByFlightIdOrdered(flightId) : seats.findAllByFlightIdOrdered(flightId);
    }

    public Optional<Seat> seat(Long flightId, String seatNumber) {
        return seats.findByFlightIdAndSeatNumberIgnoreCase(flightId, seatNumber.trim());
    }

    public int availableSeatCount(Long flightId) {
        return (int) seats.countByFlightIdAndAvailableTrue(flightId);
    }

    // ------------------------------------------------------------ mutations

    @Transactional
    public Airline createAirline(CreateAirlineInput in) {
        String code = in.iataCode().trim().toUpperCase();
        airlines.findByIataCode(code).ifPresent(a -> {
            throw new ApiException(ErrorCode.CONFLICT, "Airline with IATA code " + code + " already exists");
        });
        return airlines.save(new Airline(code, in.name().trim(), in.country().trim()));
    }

    @Transactional
    public Aircraft createAircraft(CreateAircraftInput in) {
        Airline airline = airlines.findById(in.airlineId()).orElseThrow(() -> ApiException.notFound("Airline", in.airlineId()));
        return aircraft.save(new Aircraft(in.registration().trim().toUpperCase(), in.model().trim(), in.totalSeats(), airline));
    }

    @Transactional
    public Flight createFlight(CreateFlightInput in) {
        if (!in.scheduledArrival().isAfter(in.scheduledDeparture())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "scheduledArrival must be after scheduledDeparture");
        }
        Airline airline = airlines.findById(in.airlineId()).orElseThrow(() -> ApiException.notFound("Airline", in.airlineId()));
        Aircraft ac = aircraft.findById(in.aircraftId()).orElseThrow(() -> ApiException.notFound("Aircraft", in.aircraftId()));

        Flight flight = flights.save(new Flight(in.flightNumber().trim().toUpperCase(), airline, ac,
                in.origin().trim().toUpperCase(), in.destination().trim().toUpperCase(),
                in.scheduledDeparture(), in.scheduledArrival(), in.gate(), in.basePrice()));

        List<Seat> generated = new ArrayList<>();
        for (SeatGenerator.SeatSpec spec : SeatGenerator.generate(ac.getTotalSeats())) {
            generated.add(new Seat(flight, spec.seatNumber(), spec.seatClass()));
        }
        seats.saveAll(generated);

        events.publish(FlightEvents.CREATED, FlightEvents.created(flight));
        log.info("Created flight {} ({}) with {} seats", flight.getFlightNumber(), flight.getId(), generated.size());
        return flight;
    }

    @Transactional
    public Flight updateStatus(Long flightId, FlightStatus newStatus) {
        Flight flight = requireFlight(flightId);
        FlightStatus old = flight.getStatus();
        if (old == FlightStatus.CANCELLED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Flight " + flight.getFlightNumber() + " is already cancelled");
        }
        if (old == newStatus) {
            return flight;
        }
        flight.setStatus(newStatus);
        events.publish(FlightEvents.STATUS_CHANGED, FlightEvents.statusChanged(flight, old));
        if (newStatus == FlightStatus.CANCELLED) {
            events.publish(FlightEvents.CANCELLED, FlightEvents.cancelled(flight));
        }
        log.info("Flight {} status {} -> {}", flight.getFlightNumber(), old, newStatus);
        return flight;
    }

    @Transactional
    public Flight updateGate(Long flightId, String gate) {
        Flight flight = requireFlight(flightId);
        String newGate = gate.trim().toUpperCase();
        String oldGate = flight.getGate();
        if (newGate.equals(oldGate)) {
            return flight;
        }
        flight.setGate(newGate);
        events.publish(FlightEvents.GATE_CHANGED, FlightEvents.gateChanged(flight, oldGate));
        log.info("Flight {} gate {} -> {}", flight.getFlightNumber(), oldGate, newGate);
        return flight;
    }

    /** Called from booking events: mark a seat taken/free. Idempotent by nature (sets a flag). */
    @Transactional
    public void setSeatAvailability(Long flightId, String seatNumber, boolean available) {
        Seat seat = seats.findByFlightIdAndSeatNumberIgnoreCase(flightId, seatNumber)
                .orElseThrow(() -> ApiException.notFound("Seat", flightId + "/" + seatNumber));
        seat.setAvailable(available);
        log.info("Seat {} on flight {} is now {}", seatNumber, flightId, available ? "available" : "taken");
    }
}
