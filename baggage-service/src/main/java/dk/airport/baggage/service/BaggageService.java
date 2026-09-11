package dk.airport.baggage.service;

import dk.airport.baggage.domain.*;
import dk.airport.baggage.messaging.BaggageEvents;
import dk.airport.baggage.messaging.EventPublisher;
import dk.airport.baggage.repository.BaggageRepository;
import dk.airport.baggage.repository.BookingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final BaggageRepository baggage;
    private final BookingSnapshotRepository snapshots;
    private final EventPublisher events;

    public BaggageService(BaggageRepository baggage, BookingSnapshotRepository snapshots, EventPublisher events) {
        this.baggage = baggage;
        this.snapshots = snapshots;
        this.events = events;
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

    @Transactional
    public Baggage register(String bookingReference, BigDecimal weightKg, BaggageType type) {
        String ref = bookingReference.trim().toUpperCase();
        BookingSnapshot snapshot = snapshots.findByBookingReferenceIgnoreCase(ref)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Booking " + ref + " is not known to baggage-service (no confirmed booking event received)"));

        BaggageRules.assertEligible(snapshot.getStatus());
        BaggageRules.assertWeight(weightKg);
        BaggageRules.assertCheckedLimit(baggage.countByBookingReferenceIgnoreCaseAndType(ref, BaggageType.CHECKED), type);

        Baggage bag = baggage.save(new Baggage(uniqueTag(), ref, snapshot.getPassengerName(), snapshot.getFlightNumber(),
                weightKg, type, CHECK_IN_LOCATION));
        events.publish(BaggageEvents.REGISTERED, BaggageEvents.registered(bag));
        log.info("Registered baggage {} ({} kg, {}) on booking {}", bag.getTagNumber(), weightKg, type, ref);
        return bag;
    }

    @Transactional
    public Baggage updateStatus(String tagNumber, BaggageStatus newStatus, String location) {
        Baggage bag = baggage.findByTagNumberIgnoreCase(tagNumber.trim())
                .orElseThrow(() -> ApiException.notFound("Baggage", tagNumber));
        BaggageStatus old = bag.getStatus();
        bag.moveTo(newStatus, location);
        events.publish(BaggageEvents.STATUS_CHANGED, BaggageEvents.statusChanged(bag, old, location));
        log.info("Baggage {} status {} -> {} at {}", bag.getTagNumber(), old, newStatus, bag.getLastLocation());
        return bag;
    }

    /** flight.cancelled: every bag on the flight that has not already arrived / been lost goes back to the return desk. */
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
