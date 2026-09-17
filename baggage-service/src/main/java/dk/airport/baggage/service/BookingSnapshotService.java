package dk.airport.baggage.service;

import dk.airport.baggage.domain.BookingSnapshot;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Maintains the local booking read-model from booking.* / flight.* events. Commutative (dev plan DP-31): the status
 * follows the booking lifecycle, not the arrival order (see {@link BookingSnapshot#apply}), and every change locks the
 * row first.
 */
@Service
public class BookingSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BookingSnapshotService.class);

    private final BookingSnapshotRepository snapshots;

    public BookingSnapshotService(BookingSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Transactional
    public BookingSnapshot upsert(String reference, String passengerName, String flightNumber, Long flightId,
                                  String status, OffsetDateTime occurredAt) {
        String ref = reference.trim().toUpperCase(Locale.ROOT);
        Optional<BookingSnapshot> existing = snapshots.lockByBookingReference(ref);
        if (existing.isEmpty()) {
            log.info("Booking snapshot {} -> {}", ref, status);
            return snapshots.save(new BookingSnapshot(ref, passengerName, flightNumber, flightId, status, occurredAt));
        }
        BookingSnapshot snapshot = existing.get();
        if (snapshot.apply(passengerName, flightNumber, flightId, status, occurredAt)) {
            log.info("Booking snapshot {} -> {}", ref, status);
        } else {
            log.info("Ignoring late {} for booking snapshot {}: it is already {} (event occurredAt {})",
                    status, ref, snapshot.getStatus(), occurredAt);
        }
        return snapshot;
    }

    @Transactional
    public int cancelForFlight(Long flightId, String flightNumber, OffsetDateTime occurredAt) {
        List<BookingSnapshot> affected = snapshots.lockByFlight(flightId,
                flightNumber == null || flightNumber.isBlank() ? null : flightNumber);
        affected.forEach(s -> s.cancel(occurredAt));
        return affected.size();
    }
}
