package dk.airport.baggage.service;

import dk.airport.baggage.domain.BookingSnapshot;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/** Maintains the local booking read-model from booking.* / flight.* events. */
@Service
public class BookingSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BookingSnapshotService.class);

    private final BookingSnapshotRepository snapshots;

    public BookingSnapshotService(BookingSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Transactional
    public BookingSnapshot upsert(String reference, String passengerName, String flightNumber, Long flightId, String status) {
        String ref = reference.trim().toUpperCase();
        BookingSnapshot snapshot = snapshots.findById(ref)
                .map(existing -> { existing.update(passengerName, flightNumber, flightId, status); return existing; })
                .orElseGet(() -> new BookingSnapshot(ref, passengerName, flightNumber, flightId, status));
        log.info("Booking snapshot {} -> {}", ref, status);
        return snapshots.save(snapshot);
    }

    @Transactional
    public int cancelForFlight(Long flightId, String flightNumber) {
        Set<BookingSnapshot> affected = new LinkedHashSet<>();
        if (flightId != null) affected.addAll(snapshots.findByFlightId(flightId));
        if (flightNumber != null && !flightNumber.isBlank()) affected.addAll(snapshots.findByFlightNumberIgnoreCase(flightNumber));
        affected.forEach(s -> s.setStatus("CANCELLED"));
        return affected.size();
    }
}
