package dk.airport.booking.repository;

import dk.airport.booking.domain.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {
    Optional<Passenger> findByEmailIgnoreCase(String email);
}
