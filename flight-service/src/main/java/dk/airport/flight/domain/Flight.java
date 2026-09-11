package dk.airport.flight.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "flight")
public class Flight {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_number", nullable = false, length = 10)
    private String flightNumber;

    @ManyToOne(optional = false)
    @JoinColumn(name = "airline_id")
    private Airline airline;

    @ManyToOne(optional = false)
    @JoinColumn(name = "aircraft_id")
    private Aircraft aircraft;

    @Column(nullable = false, length = 3)
    private String origin;

    @Column(nullable = false, length = 3)
    private String destination;

    @Column(name = "scheduled_departure", nullable = false)
    private OffsetDateTime scheduledDeparture;

    @Column(name = "scheduled_arrival", nullable = false)
    private OffsetDateTime scheduledArrival;

    @Column(length = 10)
    private String gate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FlightStatus status;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false, length = 3)
    private String currency = "DKK";

    protected Flight() {}

    public Flight(String flightNumber, Airline airline, Aircraft aircraft, String origin, String destination,
                  OffsetDateTime scheduledDeparture, OffsetDateTime scheduledArrival, String gate, BigDecimal basePrice) {
        this.flightNumber = flightNumber;
        this.airline = airline;
        this.aircraft = aircraft;
        this.origin = origin;
        this.destination = destination;
        this.scheduledDeparture = scheduledDeparture;
        this.scheduledArrival = scheduledArrival;
        this.gate = gate;
        this.status = FlightStatus.SCHEDULED;
        this.basePrice = basePrice;
    }

    public Long getId() { return id; }
    public String getFlightNumber() { return flightNumber; }
    public Airline getAirline() { return airline; }
    public Aircraft getAircraft() { return aircraft; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public OffsetDateTime getScheduledDeparture() { return scheduledDeparture; }
    public OffsetDateTime getScheduledArrival() { return scheduledArrival; }
    public String getGate() { return gate; }
    public FlightStatus getStatus() { return status; }
    public BigDecimal getBasePrice() { return basePrice; }
    public String getCurrency() { return currency; }

    public void setGate(String gate) { this.gate = gate; }
    public void setStatus(FlightStatus status) { this.status = status; }
}
