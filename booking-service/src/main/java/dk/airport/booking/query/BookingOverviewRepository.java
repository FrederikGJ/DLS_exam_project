package dk.airport.booking.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.airport.booking.query.BookingOverview.Baggage;
import dk.airport.booking.query.BookingOverview.Details;
import dk.airport.booking.query.BookingOverview.Payment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

@Repository
public class BookingOverviewRepository {

    private static final TypeReference<Map<Long, Payment>> PAYMENTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Baggage>> BAGS = new TypeReference<>() {};
    static final Comparator<Payment> LATEST_PAYMENT = Comparator
            .comparing((Payment payment) -> payment.statusAt().toInstant()).thenComparing(Payment::paymentId);

    private static final String BOOKING_SNAPSHOT = """
            INSERT INTO booking_overview (booking_reference, booking)
            SELECT b.booking_reference,
                   (to_jsonb(b) - 'passenger_id') || jsonb_build_object('passenger', to_jsonb(p))
            FROM booking b JOIN passenger p ON p.id = b.passenger_id
            """;
    private static final String UPSERT_BOOKING = """
             ON CONFLICT (booking_reference) DO UPDATE SET booking = EXCLUDED.booking
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public BookingOverviewRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<BookingOverview> find(String reference) {
        return jdbc.sql("""
                SELECT booking, payments, baggage, latest_payment_status, baggage_count
                FROM booking_overview WHERE booking_reference = :ref
                """).param("ref", reference).query((rs, index) -> {
                    Map<Long, Payment> payments = read(rs.getString("payments"), PAYMENTS);
                    Map<String, Baggage> baggage = read(rs.getString("baggage"), BAGS);
                    return new BookingOverview(read(rs.getString("booking"), new TypeReference<Details>() {}),
                            rs.getString("latest_payment_status"), rs.getInt("baggage_count"),
                            payments.values().stream().sorted(LATEST_PAYMENT.reversed()).toList(),
                            baggage.values().stream().sorted(Comparator.comparing(Baggage::tagNumber)).toList());
                }).optional();
    }

    void refreshPassengers(long passengerId) {
        jdbc.sql("""
                UPDATE booking_overview o SET booking = jsonb_set(o.booking, '{passenger}', to_jsonb(p))
                FROM booking b JOIN passenger p ON p.id = b.passenger_id
                WHERE p.id = :id AND o.booking_reference = b.booking_reference
                  AND o.booking -> 'passenger' IS DISTINCT FROM to_jsonb(p)
                """).param("id", passengerId).update();
    }

    void refreshBooking(long bookingId) {
        jdbc.sql(BOOKING_SNAPSHOT + " WHERE b.id = :id " + UPSERT_BOOKING)
                .param("id", bookingId).update();
    }

    void refreshFlightBookings(long flightId) {
        jdbc.sql(BOOKING_SNAPSHOT + " WHERE b.flight_id = :id ORDER BY b.booking_reference " + UPSERT_BOOKING)
                .param("id", flightId).update();
    }

    Optional<Map<Long, Payment>> lockPayments(String reference) {
        return jdbc.sql("SELECT payments FROM booking_overview WHERE booking_reference = :ref FOR UPDATE")
                .param("ref", reference).query((rs, index) -> read(rs.getString(1), PAYMENTS)).optional();
    }

    void updatePayments(String reference, Map<Long, Payment> payments) {
        String status = payments.values().stream().max(LATEST_PAYMENT).orElseThrow().status();
        jdbc.sql("""
                UPDATE booking_overview SET payments = CAST(:payments AS jsonb), latest_payment_status = :status
                WHERE booking_reference = :ref
                """).param("payments", write(payments)).param("status", status).param("ref", reference).update();
    }

    Optional<Map<String, Baggage>> lockBaggage(String reference) {
        return jdbc.sql("SELECT baggage FROM booking_overview WHERE booking_reference = :ref FOR UPDATE")
                .param("ref", reference).query((rs, index) -> read(rs.getString(1), BAGS)).optional();
    }

    void updateBaggage(String reference, Map<String, Baggage> baggage) {
        jdbc.sql("""
                UPDATE booking_overview SET baggage = CAST(:baggage AS jsonb), baggage_count = :count
                WHERE booking_reference = :ref
                """).param("baggage", write(baggage)).param("count", baggage.size()).param("ref", reference).update();
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid booking projection", e);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize booking projection", e);
        }
    }
}
