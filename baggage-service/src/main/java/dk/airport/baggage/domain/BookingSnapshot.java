package dk.airport.baggage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Local read-model of a booking, populated from booking.* events.
 * booking-service is the source of truth; this is only used to validate baggage registration.
 *
 * <p>Commutative (dev plan DP-31): a booking only ever moves forward through its lifecycle - PENDING_PAYMENT, then
 * CONFIRMED, then CHECKED_IN, and CANCELLED from any of them, never back. The snapshot therefore keeps the furthest
 * status it has heard of, so booking.created, booking.confirmed, booking.checkedin, booking.cancelled and
 * flight.cancelled give the same snapshot in any order, without comparing clocks of two producers.
 */
@Entity
@Table(name = "booking_snapshot")
public class BookingSnapshot {
    @Id
    @Column(name = "booking_reference", length = 6)
    private String bookingReference;

    @Column(name = "passenger_name", nullable = false, length = 200)
    private String passengerName;

    @Column(name = "flight_number", nullable = false, length = 10)
    private String flightNumber;

    @Column(name = "flight_id")
    private Long flightId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Newest occurredAt of the events applied - diagnostics only, the status is ordered by the lifecycle. */
    @Column(name = "event_occurred_at")
    private OffsetDateTime eventOccurredAt;

    /** The booking lifecycle in order; a status later in the list is never replaced by an earlier one. */
    public static final List<String> LIFECYCLE = List.of("PENDING_PAYMENT", "CONFIRMED", "CHECKED_IN", "CANCELLED");

    protected BookingSnapshot() {}

    public BookingSnapshot(String bookingReference, String passengerName, String flightNumber, Long flightId,
                           String status, OffsetDateTime occurredAt) {
        this.bookingReference = bookingReference;
        this.passengerName = passengerName;
        this.flightNumber = flightNumber;
        this.flightId = flightId;
        this.status = status;
        touch(occurredAt);
    }

    public String getBookingReference() { return bookingReference; }
    public String getPassengerName() { return passengerName; }
    public String getFlightNumber() { return flightNumber; }
    public Long getFlightId() { return flightId; }
    public String getStatus() { return status; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getEventOccurredAt() { return eventOccurredAt; }

    /**
     * Applies a booking event. The descriptive fields are filled in; the status only moves forward in
     * {@link #LIFECYCLE}.
     *
     * @return false if the event's status is behind the current one (a late event) and was ignored
     */
    public boolean apply(String passengerName, String flightNumber, Long flightId, String newStatus,
                         OffsetDateTime occurredAt) {
        if (passengerName != null && !passengerName.isBlank()) this.passengerName = passengerName;
        if (flightNumber != null && !flightNumber.isBlank()) this.flightNumber = flightNumber;
        if (flightId != null) this.flightId = flightId;
        boolean current = LIFECYCLE.indexOf(newStatus) >= LIFECYCLE.indexOf(status);
        if (current) {
            this.status = newStatus;
        }
        touch(occurredAt);
        return current;
    }

    /** flight.cancelled: CANCELLED is the end of the lifecycle, so it always applies. */
    public void cancel(OffsetDateTime occurredAt) {
        this.status = "CANCELLED";
        touch(occurredAt);
    }

    private void touch(OffsetDateTime occurredAt) {
        if (occurredAt != null && (eventOccurredAt == null || occurredAt.isAfter(eventOccurredAt))) {
            eventOccurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);     // the precision PostgreSQL stores
        }
        updatedAt = OffsetDateTime.now();
    }
}
