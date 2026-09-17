package dk.airport.flight.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "seat")
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "flight_id")
    private Flight flight;

    @Column(name = "seat_number", nullable = false, length = 5)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_class", nullable = false, length = 20)
    private SeatClass seatClass;

    @Column(name = "is_available", nullable = false)
    private boolean available = true;

    /** occurredAt of the booking event that last decided {@code available}; null until the first one. */
    @Column(name = "availability_changed_at")
    private OffsetDateTime availabilityChangedAt;

    protected Seat() {}

    public Seat(Flight flight, String seatNumber, SeatClass seatClass) {
        this.flight = flight;
        this.seatNumber = seatNumber;
        this.seatClass = seatClass;
    }

    public Long getId() { return id; }
    public Flight getFlight() { return flight; }
    public String getSeatNumber() { return seatNumber; }
    public SeatClass getSeatClass() { return seatClass; }
    public boolean isAvailable() { return available; }
    public OffsetDateTime getAvailabilityChangedAt() { return availabilityChangedAt; }

    /**
     * Applies what a booking event says about the seat (booking.confirmed: taken, booking.cancelled: free) unless an
     * event that happened later has already decided it - last writer wins on the event's {@code occurredAt}. All
     * seat events come from booking-service, so the timestamps are from one clock. On an exact tie "taken" wins,
     * so the result is the same in both orders. Applying the same event twice changes nothing.
     *
     * @return false if the event was older than the current state and therefore ignored
     */
    public boolean applyAvailability(boolean newAvailable, OffsetDateTime eventTime) {
        // PostgreSQL keeps microseconds; comparing at the stored precision makes a re-read row compare like this one
        OffsetDateTime occurredAt = eventTime.truncatedTo(ChronoUnit.MICROS);
        if (availabilityChangedAt != null) {
            if (occurredAt.isBefore(availabilityChangedAt)) {
                return false;
            }
            if (occurredAt.isEqual(availabilityChangedAt) && newAvailable && !available) {
                return false;
            }
        }
        available = newAvailable;
        availabilityChangedAt = occurredAt;
        return true;
    }
}
