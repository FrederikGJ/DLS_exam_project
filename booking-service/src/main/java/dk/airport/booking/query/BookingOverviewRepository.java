package dk.airport.booking.query;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BookingOverviewRepository extends JpaRepository<BookingOverview, Long> {

    // ------------------------------------------------------------ read side (BookingQueryService)

    Optional<BookingOverview> findByBookingReference(String bookingReference);

    List<BookingOverview> findByPassengerEmailOrderByCreatedAtDesc(String passengerEmail);

    // ------------------------------------------------------------ projector: row lock per booking

    /*
     * The projector locks the row before changing it. Payment events, baggage events and the booking's own commands
     * are handled on different threads (and pods) and each rewrites the whole row, so without the lock two of them
     * could read the same version and the later commit would silently drop the other's change.
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from BookingOverview o where o.bookingId = :bookingId")
    Optional<BookingOverview> lockByBookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from BookingOverview o where o.bookingReference = :bookingReference")
    Optional<BookingOverview> lockByBookingReference(String bookingReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from BookingOverview o where o.passengerId = :passengerId")
    List<BookingOverview> lockByPassengerId(Long passengerId);
}
