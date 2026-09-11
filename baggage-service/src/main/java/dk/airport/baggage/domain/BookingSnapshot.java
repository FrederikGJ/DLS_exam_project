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

    protected BookingSnapshot() {}

    public BookingSnapshot(String bookingReference, String passengerName, String flightNumber, Long flightId, String status) {
        this.bookingReference = bookingReference;
        this.passengerName = passengerName;
        this.flightNumber = flightNumber;
        this.flightId = flightId;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getBookingReference() { return bookingReference; }
    public String getPassengerName() { return passengerName; }
    public String getFlightNumber() { return flightNumber; }
    public Long getFlightId() { return flightId; }
    public String getStatus() { return status; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void update(String passengerName, String flightNumber, Long flightId, String status) {
        if (passengerName != null && !passengerName.isBlank()) this.passengerName = passengerName;
        if (flightNumber != null && !flightNumber.isBlank()) this.flightNumber = flightNumber;
        if (flightId != null) this.flightId = flightId;
        if (status != null && !status.isBlank()) this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }
}
