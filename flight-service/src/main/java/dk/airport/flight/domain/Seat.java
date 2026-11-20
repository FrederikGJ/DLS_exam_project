package dk.airport.flight.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

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

    /** Booking reference of the last booking event applied to this seat (ticket 10). */
    @Column(name = "held_by_booking", length = 6)
    private String heldByBooking;

    /** occurredAt of that event. */
    @Column(name = "availability_event_at")
    private OffsetDateTime availabilityEventAt;

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
    public void setAvailable(boolean available) { this.available = available; }
    public String getHeldByBooking() { return heldByBooking; }
    public OffsetDateTime getAvailabilityEventAt() { return availabilityEventAt; }

    /**
     * booking.confirmed: the booking takes the seat. Ignored when
     * <ul>
     *   <li>the same booking already released the seat (its booking.cancelled arrived first - CANCELLED is
     *       terminal), or</li>
     *   <li>the event is older than the last one applied (another booking took the seat later).</li>
     * </ul>
     * @return true if the seat changed
     */
    public boolean takeFor(String bookingReference, OffsetDateTime occurredAt) {
        String ref = normalize(bookingReference);
        if (ref.equals(heldByBooking)) {
            // available + same holder = this booking was cancelled already; not available = duplicate
            return false;
        }
        if (availabilityEventAt != null && occurredAt.isBefore(availabilityEventAt)) {
            return false;
        }
        this.available = false;
        this.heldByBooking = ref;
        this.availabilityEventAt = occurredAt;
        return true;
    }

    /**
     * booking.cancelled: the seat is freed - but only if this booking holds it (or nobody has yet).
     * A cancel for a booking that no longer holds the seat (someone else booked it since) is ignored.
     * The booking is remembered as holder, so its booking.confirmed arriving late cannot take the seat again.
     * @return true if the seat changed
     */
    public boolean releaseFor(String bookingReference, OffsetDateTime occurredAt) {
        String ref = normalize(bookingReference);
        if (heldByBooking != null && !ref.equals(heldByBooking)) {
            return false;
        }
        boolean changed = !available || heldByBooking == null;
        this.available = true;
        this.heldByBooking = ref;
        if (availabilityEventAt == null || occurredAt.isAfter(availabilityEventAt)) {
            this.availabilityEventAt = occurredAt;
        }
        return changed;
    }

    private static String normalize(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase();
    }
}
