package dk.airport.booking.repository;

import dk.airport.booking.domain.Booking;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    boolean existsByBookingReference(String bookingReference);

    @Query("select b from Booking b where lower(b.passenger.email) = lower(:email) order by b.createdAt desc")
    List<Booking> findByPassengerEmail(String email);

    List<Booking> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);

    /** Ordered, so two transactions that walk the same flight's bookings take the row locks in the same order. */
    List<Booking> findByFlightIdOrderById(Long flightId);

    /** Unpaid bookings created before {@code cutoff}, oldest first (partial index idx_booking_pending_payment). */
    @Query("select b.id from Booking b where b.status = dk.airport.booking.domain.BookingStatus.PENDING_PAYMENT and b.createdAt < :cutoff order by b.createdAt")
    List<Long> findUnpaidCreatedBefore(OffsetDateTime cutoff, Limit limit);

    @Query("select count(b) > 0 from Booking b where b.flightId = :flightId and upper(b.seatNumber) = upper(:seatNumber) and b.status <> dk.airport.booking.domain.BookingStatus.CANCELLED")
    boolean existsActiveSeat(Long flightId, String seatNumber);
}
