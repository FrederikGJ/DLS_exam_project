package dk.airport.flight.repository;

import dk.airport.flight.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("select s from Seat s join fetch s.flight where s.flight.id = :flightId order by length(s.seatNumber), s.seatNumber")
    List<Seat> findAllByFlightIdOrdered(Long flightId);

    @Query("select s from Seat s join fetch s.flight where s.flight.id = :flightId and s.available = true order by length(s.seatNumber), s.seatNumber")
    List<Seat> findAvailableByFlightIdOrdered(Long flightId);

    Optional<Seat> findByFlightIdAndSeatNumberIgnoreCase(Long flightId, String seatNumber);

    long countByFlightIdAndAvailableTrue(Long flightId);
}
