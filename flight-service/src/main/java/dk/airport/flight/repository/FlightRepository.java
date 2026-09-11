package dk.airport.flight.repository;

import dk.airport.flight.domain.Flight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, Long>, JpaSpecificationExecutor<Flight> {

    List<Flight> findByAirlineIdOrderByScheduledDeparture(Long airlineId);

    Optional<Flight> findFirstByFlightNumberIgnoreCaseAndScheduledDepartureGreaterThanEqualAndScheduledDepartureLessThanOrderByScheduledDeparture(
            String flightNumber, OffsetDateTime from, OffsetDateTime to);
}
