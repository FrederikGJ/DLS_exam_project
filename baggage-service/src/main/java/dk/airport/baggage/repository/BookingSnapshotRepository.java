package dk.airport.baggage.repository;

import dk.airport.baggage.domain.BookingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingSnapshotRepository extends JpaRepository<BookingSnapshot, String> {
    Optional<BookingSnapshot> findByBookingReferenceIgnoreCase(String bookingReference);
    List<BookingSnapshot> findByFlightId(Long flightId);
    List<BookingSnapshot> findByFlightNumberIgnoreCase(String flightNumber);
}
