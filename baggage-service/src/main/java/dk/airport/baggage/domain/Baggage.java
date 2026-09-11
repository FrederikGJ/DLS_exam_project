package dk.airport.baggage.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "baggage")
public class Baggage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tag_number", nullable = false, unique = true, length = 12)
    private String tagNumber;

    @Column(name = "booking_reference", nullable = false, length = 6)
    private String bookingReference;

    @Column(name = "passenger_name", nullable = false, length = 200)
    private String passengerName;

    @Column(name = "flight_number", nullable = false, length = 10)
    private String flightNumber;

    @Column(name = "weight_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BaggageType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BaggageStatus status;

    @Column(name = "last_location", length = 100)
    private String lastLocation;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Baggage() {}

    public Baggage(String tagNumber, String bookingReference, String passengerName, String flightNumber,
                   BigDecimal weightKg, BaggageType type, String lastLocation) {
        this.tagNumber = tagNumber;
        this.bookingReference = bookingReference;
        this.passengerName = passengerName;
        this.flightNumber = flightNumber;
        this.weightKg = weightKg;
        this.type = type;
        this.status = BaggageStatus.REGISTERED;
        this.lastLocation = lastLocation;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getTagNumber() { return tagNumber; }
    public String getBookingReference() { return bookingReference; }
    public String getPassengerName() { return passengerName; }
    public String getFlightNumber() { return flightNumber; }
    public BigDecimal getWeightKg() { return weightKg; }
    public BaggageType getType() { return type; }
    public BaggageStatus getStatus() { return status; }
    public String getLastLocation() { return lastLocation; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** Moves the bag to a new status/location. A null location keeps the previous one. */
    public void moveTo(BaggageStatus newStatus, String location) {
        this.status = newStatus;
        if (location != null && !location.isBlank()) {
            this.lastLocation = location.trim();
        }
        this.updatedAt = OffsetDateTime.now();
    }
}
