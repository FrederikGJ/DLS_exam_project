package dk.airport.booking.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A booking of one seat on one flight. Holds a snapshot of flight data (flight-service is the source of truth).
 * State transitions are implemented here so they can be unit tested without Spring.
 */
@Entity
@Table(name = "booking")
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "seat_number", nullable = false, length = 5)
    private String seatNumber;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency = "DKK";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

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

    /** Any non-cancelled status -> CANCELLED. */
    public void cancel() {
        if (status == BookingStatus.CANCELLED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Booking " + bookingReference + " is already cancelled");
        }
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

    public void updateFlightSnapshot(String flightStatus, String gate) {
        boolean changed = false;
        if (flightStatus != null && !flightStatus.equals(this.flightStatus)) {
            this.flightStatus = flightStatus;
            changed = true;
        }
        if (gate != null && !gate.equals(this.gate)) {
            this.gate = gate;
            changed = true;
        }
        if (changed) {
            touch();
        }
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
    public String getSeatNumber() { return seatNumber; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public BookingStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
