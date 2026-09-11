package dk.airport.booking.repository;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    boolean existsByBookingReference(String bookingReference);

    @Query("select b from Booking b where lower(b.passenger.email) = lower(:email) order by b.createdAt desc")
    List<Booking> findByPassengerEmail(String email);

    List<Booking> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);

    @Query("select b from Booking b where b.flightId = :flightId and b.status <> :excluded")
    List<Booking> findByFlightIdAndStatusNot(Long flightId, BookingStatus excluded);

    List<Booking> findByFlightId(Long flightId);

    @Query("select count(b) > 0 from Booking b where b.flightId = :flightId and upper(b.seatNumber) = upper(:seatNumber) and b.status <> dk.airport.booking.domain.BookingStatus.CANCELLED")
    boolean existsActiveSeat(Long flightId, String seatNumber);
}
