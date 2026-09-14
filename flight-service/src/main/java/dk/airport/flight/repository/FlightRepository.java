package dk.airport.flight.repository;

import dk.airport.flight.domain.Flight;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface FlightRepository extends JpaRepository<Flight, Long>, JpaSpecificationExecutor<Flight> {

    List<Flight> findByAirlineIdOrderByScheduledDeparture(Long airlineId);

    /** Flights with the given number departing in {@code [from, to)}, earliest first ({@code Limit.of(1)} for one). */
    @Query("""
            select f from Flight f
            where upper(f.flightNumber) = upper(:flightNumber)
              and f.scheduledDeparture >= :from and f.scheduledDeparture < :to
            order by f.scheduledDeparture
            """)
    List<Flight> findByFlightNumberDepartingBetween(String flightNumber, OffsetDateTime from, OffsetDateTime to,
                                                    Limit limit);
}
