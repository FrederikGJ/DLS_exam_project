package dk.airport.flight.repository;

import dk.airport.flight.domain.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("select s from Seat s join fetch s.flight where s.flight.id = :flightId order by length(s.seatNumber), s.seatNumber")
    List<Seat> findAllByFlightIdOrdered(Long flightId);

    @Query("select s from Seat s join fetch s.flight where s.flight.id = :flightId and s.available = true order by length(s.seatNumber), s.seatNumber")
    List<Seat> findAvailableByFlightIdOrdered(Long flightId);

    Optional<Seat> findByFlightIdAndSeatNumberIgnoreCase(Long flightId, String seatNumber);

    /** Row lock for the booking-event handler: with several pods two events for one seat can arrive at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.flight.id = :flightId and upper(s.seatNumber) = upper(:seatNumber)")
    Optional<Seat> lockByFlightIdAndSeatNumber(Long flightId, String seatNumber);

    long countByFlightIdAndAvailableTrue(Long flightId);
}
