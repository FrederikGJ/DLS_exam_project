package dk.airport.booking.repository;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    boolean existsByBookingReference(String bookingReference);

    @Query("select b from Booking b where lower(b.passenger.email) = lower(:email) order by b.createdAt desc, b.id desc")
    List<Booking> findByPassengerEmail(String email);

    /** Ticket 17.1: same, one page (limit/offset in SQL, no count query because the return type is a List). */
    @Query("select b from Booking b where lower(b.passenger.email) = lower(:email) order by b.createdAt desc, b.id desc")
    List<Booking> findByPassengerEmail(String email, Pageable page);

    List<Booking> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);

    @Query("select b from Booking b where b.flightId = :flightId and b.status <> :excluded")
    List<Booking> findByFlightIdAndStatusNot(Long flightId, BookingStatus excluded);

    List<Booking> findByFlightId(Long flightId);

    @Query("select count(b) > 0 from Booking b where b.flightId = :flightId and upper(b.seatNumber) = upper(:seatNumber) and b.status <> dk.airport.booking.domain.BookingStatus.CANCELLED")
    boolean existsActiveSeat(Long flightId, String seatNumber);

    /**
     * Ticket 11: the booking row locked for the rest of the transaction, so the payment timeout and payment events
     * for the same booking are applied one after the other (also when they run in different pods).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.bookingReference = :reference")
    Optional<Booking> findByBookingReferenceForUpdate(String reference);

    /**
     * Ticket 11: PENDING_PAYMENT bookings created before {@code cutoff}, locked. SKIP LOCKED lets several pods run
     * the timeout job at once without blocking each other or cancelling the same booking twice.
     */
    @Query(value = """
            SELECT * FROM booking
            WHERE status = 'PENDING_PAYMENT' AND created_at < :cutoff
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Booking> lockExpiredPendingPayments(OffsetDateTime cutoff, int limit);
}
