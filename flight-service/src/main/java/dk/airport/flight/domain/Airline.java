package dk.airport.flight.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "airline")
public class Airline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "iata_code", nullable = false, unique = true, length = 3)
    private String iataCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String country;

    protected Airline() {}

    public Airline(String iataCode, String name, String country) {
        this.iataCode = iataCode;
        this.name = name;
        this.country = country;
    }

    public Long getId() { return id; }
    public String getIataCode() { return iataCode; }
    public String getName() { return name; }
    public String getCountry() { return country; }
}
