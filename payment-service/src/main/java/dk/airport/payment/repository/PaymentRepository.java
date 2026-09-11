package dk.airport.payment.repository;

import dk.airport.payment.domain.Payment;
import dk.airport.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByBookingReferenceOrderByCreatedAt(String bookingReference);
    List<Payment> findByBookingReferenceAndStatus(String bookingReference, PaymentStatus status);
    boolean existsByBookingReferenceAndStatus(String bookingReference, PaymentStatus status);
}
