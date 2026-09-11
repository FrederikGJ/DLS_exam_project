package dk.airport.flight.repository;

import dk.airport.flight.domain.Aircraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {
    List<Aircraft> findByAirlineIdOrderByRegistration(Long airlineId);
}
