package dk.airport.baggage.repository;

import dk.airport.baggage.domain.Baggage;
import dk.airport.baggage.domain.BaggageStatus;
import dk.airport.baggage.domain.BaggageType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BaggageRepository extends JpaRepository<Baggage, Long> {
    Optional<Baggage> findByTagNumberIgnoreCase(String tagNumber);
    boolean existsByTagNumber(String tagNumber);
    List<Baggage> findByBookingReferenceIgnoreCaseOrderByCreatedAt(String bookingReference);
    List<Baggage> findByFlightNumberIgnoreCaseOrderByCreatedAt(String flightNumber);
    long countByBookingReferenceIgnoreCaseAndType(String bookingReference, BaggageType type);
    List<Baggage> findByFlightNumberIgnoreCaseAndStatusNotIn(String flightNumber, Collection<BaggageStatus> statuses);
}
