package dk.airport.booking.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "passenger")
public class Passenger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "passport_number", nullable = false, length = 20)
    private String passportNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    protected Passenger() {}

    public Passenger(String firstName, String lastName, String email, String passportNumber, LocalDate dateOfBirth) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.passportNumber = passportNumber;
        this.dateOfBirth = dateOfBirth;
    }

    public void update(String firstName, String lastName, String passportNumber, LocalDate dateOfBirth) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.passportNumber = passportNumber;
        if (dateOfBirth != null) {
            this.dateOfBirth = dateOfBirth;
        }
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPassportNumber() { return passportNumber; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
}
