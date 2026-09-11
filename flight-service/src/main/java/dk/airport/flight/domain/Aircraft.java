package dk.airport.flight.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "aircraft")
public class Aircraft {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String registration;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @ManyToOne(optional = false)
    @JoinColumn(name = "airline_id")
    private Airline airline;

    protected Aircraft() {}

    public Aircraft(String registration, String model, Integer totalSeats, Airline airline) {
        this.registration = registration;
        this.model = model;
        this.totalSeats = totalSeats;
        this.airline = airline;
    }

    public Long getId() { return id; }
    public String getRegistration() { return registration; }
    public String getModel() { return model; }
    public Integer getTotalSeats() { return totalSeats; }
    public Airline getAirline() { return airline; }
}
