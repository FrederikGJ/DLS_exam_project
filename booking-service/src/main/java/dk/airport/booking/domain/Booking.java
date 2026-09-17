package dk.airport.booking.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A booking of one seat on one flight. Holds a snapshot of flight data (flight-service is the source of truth).
 * State transitions are implemented here so they can be unit tested without Spring.
 *
 * <p>{@code @Version}: payment events, flight events and mutations change the same row on different threads, so a
 * transaction that read an older version fails at commit (and is retried by the listener) instead of overwriting.
 */
@Entity
@Table(name = "booking")
public class Booking {

    private static final String FLIGHT_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(name = "booking_reference", nullable = false, unique = true, length = 6)
    private String bookingReference;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "passenger_id")
    private Passenger passenger;

    @Column(name = "flight_id", nullable = false)
    private Long flightId;

    @Column(name = "flight_number", nullable = false, length = 10)
    private String flightNumber;

    @Column(name = "departure_time", nullable = false)
    private OffsetDateTime departureTime;

    @Column(length = 10)
    private String gate;

    @Column(name = "flight_status", length = 20)
    private String flightStatus;

    /** occurredAt of the newest flight event seen for {@code flightStatus} (null: value from booking time). */
    @Column(name = "flight_status_changed_at")
    private OffsetDateTime flightStatusChangedAt;

    /** occurredAt of the newest flight event seen for {@code gate} (null: value from booking time). */
    @Column(name = "gate_changed_at")
    private OffsetDateTime gateChangedAt;

    @Column(name = "seat_number", nullable = false, length = 5)
    private String seatNumber;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency = "DKK";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    /** Why the booking was cancelled (null unless CANCELLED). */
    @Column(name = "cancellation_reason", length = 200)
    private String cancellationReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Booking() {}

    public Booking(String bookingReference, Passenger passenger, Long flightId, String flightNumber,
                   OffsetDateTime departureTime, String gate, String flightStatus, String seatNumber,
                   BigDecimal price, String currency) {
        this.bookingReference = bookingReference;
        this.passenger = passenger;
        this.flightId = flightId;
        this.flightNumber = flightNumber;
        this.departureTime = departureTime;
        this.gate = gate;
        this.flightStatus = flightStatus;
        this.seatNumber = seatNumber;
        this.price = price;
        this.currency = currency == null ? "DKK" : currency;
        this.status = BookingStatus.PENDING_PAYMENT;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    // ------------------------------------------------------------ state transitions

    /** PENDING_PAYMENT -> CONFIRMED. */
    public void confirm() {
        if (status != BookingStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "Booking " + bookingReference + " cannot be confirmed from status " + status);
        }
        transition(BookingStatus.CONFIRMED);
    }

    /** Any non-cancelled status -> CANCELLED, remembering why. */
    public void cancel(String reason) {
        if (status == BookingStatus.CANCELLED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Booking " + bookingReference + " is already cancelled");
        }
        cancellationReason = reason;
        transition(BookingStatus.CANCELLED);
    }

    /** CONFIRMED -> CHECKED_IN. */
    public void checkIn() {
        if (status != BookingStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "Booking " + bookingReference + " cannot be checked in from status " + status);
        }
        transition(BookingStatus.CHECKED_IN);
    }

    public boolean isCancelled() {
        return status == BookingStatus.CANCELLED;
    }

    private void transition(BookingStatus newStatus) {
        this.status = newStatus;
        touch();
    }

    // ------------------------------------------------------------ snapshot updates

    /**
     * Applies flight data from a flight.* event that happened at {@code eventTime}; {@code null} means the event does
     * not say. Commutative (dev plan DP-31), so the flight events for this booking may arrive in any order:
     * <ul>
     *   <li>status and gate are each last-writer-wins on the event time (all flight events come from flight-service,
     *       one clock); on an exact tie the alphabetically greater value wins, in both orders;</li>
     *   <li>a CANCELLED flight stays CANCELLED - flight-service never changes a cancelled flight again.</li>
     * </ul>
     *
     * @return true if the snapshot changed
     */
    public boolean applyFlightSnapshot(String newFlightStatus, String newGate, OffsetDateTime eventTime) {
        OffsetDateTime at = eventTime.truncatedTo(ChronoUnit.MICROS);   // the precision PostgreSQL stores
        boolean changed = false;
        if (newFlightStatus != null) {
            boolean applies = !FLIGHT_CANCELLED.equals(flightStatus)
                    && (FLIGHT_CANCELLED.equals(newFlightStatus)
                        || wins(at, newFlightStatus, flightStatusChangedAt, flightStatus));
            if (applies && !newFlightStatus.equals(flightStatus)) {
                flightStatus = newFlightStatus;
                changed = true;
            }
            flightStatusChangedAt = latest(flightStatusChangedAt, at);
        }
        if (newGate != null) {
            if (wins(at, newGate, gateChangedAt, gate) && !newGate.equals(gate)) {
                gate = newGate;
                changed = true;
            }
            gateChangedAt = latest(gateChangedAt, at);
        }
        if (changed) {
            touch();
        }
        return changed;
    }

    private static boolean wins(OffsetDateTime at, String value, OffsetDateTime currentAt, String currentValue) {
        if (currentAt == null || at.isAfter(currentAt)) {
            return true;
        }
        return at.isEqual(currentAt) && value.compareTo(String.valueOf(currentValue)) > 0;
    }

    private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
        return a == null || b.isAfter(a) ? b : a;
    }

    private void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    // ------------------------------------------------------------ getters

    public Long getId() { return id; }
    public String getBookingReference() { return bookingReference; }
    public Passenger getPassenger() { return passenger; }
    public Long getFlightId() { return flightId; }
    public String getFlightNumber() { return flightNumber; }
    public OffsetDateTime getDepartureTime() { return departureTime; }
    public String getGate() { return gate; }
    public String getFlightStatus() { return flightStatus; }
    public OffsetDateTime getFlightStatusChangedAt() { return flightStatusChangedAt; }
    public OffsetDateTime getGateChangedAt() { return gateChangedAt; }
    public String getSeatNumber() { return seatNumber; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public BookingStatus getStatus() { return status; }
    public String getCancellationReason() { return cancellationReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
