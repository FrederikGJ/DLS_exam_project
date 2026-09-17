package dk.airport.booking.query;

import dk.airport.booking.domain.Booking;
import dk.airport.booking.domain.Passenger;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * Read model of one booking (CQRS, dev plan DP-28): the booking, its passenger, payments and baggage in one row -
 * exactly what "Min booking" shows. Written only by {@link BookingOverviewProjector}, read only by
 * {@link BookingQueryService}. The write model ({@link Booking}, {@link Passenger}) never reads it.
 *
 * <p>Booking and passenger fields are a copy of the write model, taken in the same transaction as the command that
 * changed it. {@code payments} and {@code baggage} are JSON arrays merged from other services' events.
 */
@Entity
@Table(name = "booking_overview")
public class BookingOverview implements Persistable<Long> {

    private static final Comparator<OverviewPayment> PAYMENT_ORDER = Comparator
            .comparing(OverviewPayment::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(OverviewPayment::paymentId);
    private static final Comparator<OverviewBaggage> BAGGAGE_ORDER = Comparator
            .comparing(OverviewBaggage::registeredAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(OverviewBaggage::tagNumber);

    @Id
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "booking_reference", nullable = false, unique = true, length = 6)
    private String bookingReference;

    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "cancellation_reason", length = 200)
    private String cancellationReason;

    @Column(name = "passenger_id", nullable = false)
    private Long passengerId;
    @Column(name = "passenger_email", nullable = false)
    private String passengerEmail;
    @Column(name = "passenger_first_name", nullable = false, length = 100)
    private String passengerFirstName;
    @Column(name = "passenger_last_name", nullable = false, length = 100)
    private String passengerLastName;
    @Column(name = "passport_number", nullable = false, length = 20)
    private String passportNumber;
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

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
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<OverviewPayment> payments = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<OverviewBaggage> baggage = List.of();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "projected_at", nullable = false)
    private OffsetDateTime projectedAt;

    /** True until the row has been inserted, so {@code save} persists instead of merging (no extra SELECT). */
    @Transient
    private boolean fresh;

    protected BookingOverview() {}

    BookingOverview(Long bookingId) {
        this.bookingId = bookingId;
        this.fresh = true;
    }

    // ------------------------------------------------------------ projections

    /** Copies the booking and its passenger from the write model. */
    void copyFrom(Booking b) {
        bookingReference = b.getBookingReference();
        status = b.getStatus().name();
        cancellationReason = b.getCancellationReason();
        flightId = b.getFlightId();
        flightNumber = b.getFlightNumber();
        departureTime = b.getDepartureTime();
        gate = b.getGate();
        flightStatus = b.getFlightStatus();
        seatNumber = b.getSeatNumber();
        price = b.getPrice();
        currency = b.getCurrency();
        createdAt = b.getCreatedAt();
        updatedAt = b.getUpdatedAt();
        copyFrom(b.getPassenger());
    }

    void copyFrom(Passenger p) {
        passengerId = p.getId();
        passengerEmail = p.getEmail().toLowerCase(Locale.ROOT);
        passengerFirstName = p.getFirstName();
        passengerLastName = p.getLastName();
        passportNumber = p.getPassportNumber();
        dateOfBirth = p.getDateOfBirth();
        touch();
    }

    void mergePayment(OverviewPayment observed) {
        payments = merged(payments, observed, OverviewPayment::paymentId, OverviewPayment::merge, PAYMENT_ORDER);
        touch();
    }

    void mergeBaggage(OverviewBaggage observed) {
        baggage = merged(baggage, observed, OverviewBaggage::tagNumber, OverviewBaggage::merge, BAGGAGE_ORDER);
        touch();
    }

    /** A new list (never mutated in place, so Hibernate's dirty check sees the change) with the line merged in. */
    private static <T, K> List<T> merged(List<T> lines, T observed, Function<T, K> key, BinaryOperator<T> merge,
                                         Comparator<T> order) {
        List<T> result = new ArrayList<>(lines.size() + 1);
        boolean found = false;
        for (T line : lines) {
            if (key.apply(line).equals(key.apply(observed))) {
                result.add(merge.apply(line, observed));
                found = true;
            } else {
                result.add(line);
            }
        }
        if (!found) {
            result.add(observed);
        }
        result.sort(order);
        return List.copyOf(result);
    }

    private void touch() {
        projectedAt = OffsetDateTime.now();
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }

    // ------------------------------------------------------------ Persistable

    @Override
    public Long getId() { return bookingId; }

    @Override
    public boolean isNew() { return fresh; }

    // ------------------------------------------------------------ getters (GraphQL type BookingOverview)

    public Long getBookingId() { return bookingId; }
    public String getBookingReference() { return bookingReference; }
    public String getStatus() { return status; }
    public String getCancellationReason() { return cancellationReason; }
    public Long getPassengerId() { return passengerId; }
    public String getPassengerEmail() { return passengerEmail; }
    public Long getFlightId() { return flightId; }
    public String getFlightNumber() { return flightNumber; }
    public OffsetDateTime getDepartureTime() { return departureTime; }
    public String getGate() { return gate; }
    public String getFlightStatus() { return flightStatus; }
    public String getSeatNumber() { return seatNumber; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public List<OverviewPayment> getPayments() { return payments; }
    public List<OverviewBaggage> getBaggage() { return baggage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getProjectedAt() { return projectedAt; }

    public OverviewPassenger getPassenger() {
        return new OverviewPassenger(passengerFirstName, passengerLastName, passengerEmail, passportNumber,
                dateOfBirth);
    }
}
