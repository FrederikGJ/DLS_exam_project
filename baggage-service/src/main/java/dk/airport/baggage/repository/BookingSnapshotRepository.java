package dk.airport.baggage.repository;

import dk.airport.baggage.domain.BookingSnapshot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BookingSnapshotRepository extends JpaRepository<BookingSnapshot, String> {
    Optional<BookingSnapshot> findByBookingReferenceIgnoreCase(String bookingReference);

    /*
     * Row locks for the event handlers: booking.* and flight.* events are consumed on two different threads (and by
     * every pod), and both change booking_snapshot.status. Without the lock, booking.confirmed could read the row
     * before flight.cancelled commits CANCELLED and then write CONFIRMED over it (lost update).
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BookingSnapshot s where s.bookingReference = :bookingReference")
    Optional<BookingSnapshot> lockByBookingReference(String bookingReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BookingSnapshot s where s.flightId = :flightId or upper(s.flightNumber) = upper(:flightNumber)"
            + " order by s.bookingReference")
    List<BookingSnapshot> lockByFlight(Long flightId, String flightNumber);
}
