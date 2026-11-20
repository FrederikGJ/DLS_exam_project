package dk.airport.baggage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Local read-model of a booking, populated from booking.* events.
 * booking-service is the source of truth; this is only used to validate baggage registration.
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

    /** occurredAt of the newest booking event applied to {@code status} (ticket 10). */
    @Column(name = "event_occurred_at")
    private OffsetDateTime eventOccurredAt;

    protected BookingSnapshot() {}

    public BookingSnapshot(String bookingReference, String passengerName, String flightNumber, Long flightId,
                           String status) {
        this(bookingReference, passengerName, flightNumber, flightId, status, OffsetDateTime.now());
    }

    public BookingSnapshot(String bookingReference, String passengerName, String flightNumber, Long flightId,
                           String status, OffsetDateTime eventOccurredAt) {
        this.bookingReference = bookingReference;
        this.passengerName = passengerName;
        this.flightNumber = flightNumber;
        this.flightId = flightId;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
        this.eventOccurredAt = eventOccurredAt;
    }

    public String getBookingReference() { return bookingReference; }
    public String getPassengerName() { return passengerName; }
    public String getFlightNumber() { return flightNumber; }
    public Long getFlightId() { return flightId; }
    public String getStatus() { return status; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public OffsetDateTime getEventOccurredAt() { return eventOccurredAt; }

    public void update(String passengerName, String flightNumber, Long flightId, String status) {
        update(passengerName, flightNumber, flightId, status, OffsetDateTime.now());
    }

    /**
     * Applies a booking event. Commutative (ticket 10):
     * <ul>
     *   <li>CANCELLED is terminal: once cancelled, nothing changes the status back, and a cancel always wins,
     *       even if it is older than the status we have (booking.cancelled before booking.confirmed ends
     *       CANCELLED).</li>
     *   <li>Otherwise the newest event wins: an event older than {@code eventOccurredAt} does not change the
     *       status.</li>
     * </ul>
     * Descriptive fields (name, flight) are the same in every booking event and are always filled in.
     *
     * @param occurredAt the event's envelope occurredAt; {@code null} (legacy event) counts as "now"
     */
    public void update(String passengerName, String flightNumber, Long flightId, String status,
                       OffsetDateTime occurredAt) {
        OffsetDateTime at = occurredAt == null ? OffsetDateTime.now() : occurredAt;
        if (passengerName != null && !passengerName.isBlank()) this.passengerName = passengerName;
        if (flightNumber != null && !flightNumber.isBlank()) this.flightNumber = flightNumber;
        if (flightId != null) this.flightId = flightId;
        if (status != null && !status.isBlank() && !CANCELLED.equals(this.status)) {
            boolean newer = eventOccurredAt == null || !at.isBefore(eventOccurredAt);
            if (CANCELLED.equals(status) || newer) {
                this.status = status;
                if (newer) this.eventOccurredAt = at;
            }
        }
        this.updatedAt = OffsetDateTime.now();
    }

    private static final String CANCELLED = "CANCELLED";

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }
}
