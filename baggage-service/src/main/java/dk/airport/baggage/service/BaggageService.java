package dk.airport.baggage.service;

import dk.airport.baggage.domain.*;
import dk.airport.baggage.messaging.BaggageEvents;
import dk.airport.baggage.messaging.EventPublisher;
import dk.airport.baggage.repository.BaggageRepository;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class BaggageService {

    private static final Logger log = LoggerFactory.getLogger(BaggageService.class);
    public static final String CHECK_IN_LOCATION = "CHECK_IN";
    public static final String RETURN_DESK_LOCATION = "RETURN_DESK";

    /** Longest idempotency key accepted (the column is VARCHAR(64); a UUID has 36 characters). */
    public static final int MAX_IDEMPOTENCY_KEY = 64;

    private final BaggageRepository baggage;
    private final BookingSnapshotRepository snapshots;
    private final EventPublisher events;
    private final TransactionTemplate tx;

    public BaggageService(BaggageRepository baggage, BookingSnapshotRepository snapshots, EventPublisher events,
                          TransactionTemplate tx) {
        this.baggage = baggage;
        this.snapshots = snapshots;
        this.events = events;
        this.tx = tx;
    }

    /**
     * Outcome of {@link #register(String, BigDecimal, BaggageType, String)}.
     *
     * @param baggage the registered bag
     * @param replayed true when an earlier request with the same idempotency key created the bag and this call only
     *     returned it (nothing was written and no event was published)
     */
    public record Registration(Baggage baggage, boolean replayed) {
    }

    // ------------------------------------------------------------ queries

    public Optional<Baggage> byTag(String tagNumber) {
        return baggage.findByTagNumberIgnoreCase(tagNumber.trim());
    }

    public List<Baggage> byBooking(String reference) {
        return baggage.findByBookingReferenceIgnoreCaseOrderByCreatedAt(reference.trim());
    }

    public List<Baggage> byFlight(String flightNumber) {
        return baggage.findByFlightNumberIgnoreCaseOrderByCreatedAt(flightNumber.trim());
    }

    public Optional<BookingSnapshot> snapshot(String reference) {
        return snapshots.findByBookingReferenceIgnoreCase(reference.trim());
    }

    // ------------------------------------------------------------ mutations

    /** Registers a bag without an idempotency key: every call creates a new bag. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Baggage register(String bookingReference, BigDecimal weightKg, BaggageType type) {
        return register(bookingReference, weightKg, type, null).baggage();
    }

    /**
     * Registers a bag, idempotently when the client sends a key (dev plan DP-30). The key names one intended
     * registration (the frontend uses a UUID per form); a repeated call with it - a double click, a retry after a
     * timeout, a duplicated HTTP request - returns the bag the first call created, writes nothing and publishes no
     * second {@code baggage.registered}. Reusing a key for a different booking, weight or type is CONFLICT.
     *
     * <p>Deliberately not one transaction: if two calls with the same key race, both miss the lookup, the loser's
     * INSERT hits the unique constraint and its transaction rolls back, and the lookup is repeated in a new
     * transaction, where it finds the winner's bag.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Registration register(String bookingReference, BigDecimal weightKg, BaggageType type,
                                 String idempotencyKey) {
        String ref = bookingReference.trim().toUpperCase();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > MAX_IDEMPOTENCY_KEY) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "idempotencyKey must be at most " + MAX_IDEMPOTENCY_KEY + " characters");
        }
        try {
            return tx.execute(status -> registerOnce(ref, weightKg, type, key));
        } catch (DataIntegrityViolationException e) {
            if (key == null) {
                throw e;
            }
            Registration winner = tx.execute(status -> baggage.findByIdempotencyKey(key)
                    .map(bag -> replay(bag, ref, weightKg, type))
                    .orElse(null));
            if (winner == null) {
                throw e;                          // not a lost race on the key (e.g. a tag collision)
            }
            return winner;
        }
    }

    private Registration registerOnce(String ref, BigDecimal weightKg, BaggageType type, String key) {
        if (key != null) {
            Optional<Baggage> earlier = baggage.findByIdempotencyKey(key);
            if (earlier.isPresent()) {
                return replay(earlier.get(), ref, weightKg, type);
            }
        }
        BookingSnapshot snapshot = snapshots.findByBookingReferenceIgnoreCase(ref)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Booking " + ref + " is not known to baggage-service (no confirmed booking event received)"));

        BaggageRules.assertEligible(snapshot.getStatus());
        BaggageRules.assertWeight(weightKg);
        BaggageRules.assertCheckedLimit(
                baggage.countByBookingReferenceIgnoreCaseAndType(ref, BaggageType.CHECKED), type);

        Baggage bag = baggage.saveAndFlush(new Baggage(uniqueTag(), ref, snapshot.getPassengerName(),
                snapshot.getFlightNumber(), weightKg, type, CHECK_IN_LOCATION, key));
        events.publish(BaggageEvents.REGISTERED, BaggageEvents.registered(bag));
        log.info("Registered baggage {} ({} kg, {}) on booking {}", bag.getTagNumber(), weightKg, type, ref);
        return new Registration(bag, false);
    }

    /** The same key must mean the same request; otherwise the client mixed up two registrations. */
    private static Registration replay(Baggage bag, String ref, BigDecimal weightKg, BaggageType type) {
        boolean same = bag.getBookingReference().equalsIgnoreCase(ref) && bag.getType() == type
                && weightKg != null && bag.getWeightKg().compareTo(weightKg) == 0;
        if (!same) {
            throw new ApiException(ErrorCode.CONFLICT, "The idempotency key was already used for another "
                    + "registration (bag " + bag.getTagNumber() + " on booking " + bag.getBookingReference() + ")");
        }
        log.info("Idempotent replay: baggage {} was already registered with this key", bag.getTagNumber());
        return new Registration(bag, true);
    }

    /**
     * Moves a bag to a new status/location. Repeating the same update (same status, and the same or no location) is
     * a no-op: nothing changes and no second {@code baggage.status.changed} is published, so a retried call is safe
     * without an idempotency key (DP-30).
     */
    @Transactional
    public Baggage updateStatus(String tagNumber, BaggageStatus newStatus, String location) {
        Baggage bag = baggage.findByTagNumberIgnoreCase(tagNumber.trim())
                .orElseThrow(() -> ApiException.notFound("Baggage", tagNumber));
        boolean sameLocation = location == null || location.isBlank() || location.trim().equals(bag.getLastLocation());
        if (bag.getStatus() == newStatus && sameLocation) {
            log.info("Baggage {} is already {} at {} - nothing to do", bag.getTagNumber(), newStatus,
                    bag.getLastLocation());
            return bag;
        }
        BaggageStatus old = bag.getStatus();
        bag.moveTo(newStatus, location);
        events.publish(BaggageEvents.STATUS_CHANGED, BaggageEvents.statusChanged(bag, old, location));
        log.info("Baggage {} status {} -> {} at {}", bag.getTagNumber(), old, newStatus, bag.getLastLocation());
        return bag;
    }

    /** flight.cancelled: every bag on the flight that has not arrived / been lost yet goes back to the return desk. */
    @Transactional
    public void returnBaggageForCancelledFlight(String flightNumber) {
        List<Baggage> affected = baggage.findByFlightNumberIgnoreCaseAndStatusNotIn(flightNumber,
                EnumSet.of(BaggageStatus.ARRIVED, BaggageStatus.LOST));
        for (Baggage bag : affected) {
            BaggageStatus old = bag.getStatus();
            bag.moveTo(BaggageStatus.REGISTERED, RETURN_DESK_LOCATION);
            events.publish(BaggageEvents.STATUS_CHANGED, BaggageEvents.statusChanged(bag, old, RETURN_DESK_LOCATION));
        }
        log.info("Flight {} cancelled: {} bag(s) sent to {}", flightNumber, affected.size(), RETURN_DESK_LOCATION);
    }

    private String uniqueTag() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String tag = TagGenerator.generate();
            if (!baggage.existsByTagNumber(tag)) {
                return tag;
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not generate a unique baggage tag");
    }
}
