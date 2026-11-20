package dk.airport.booking.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ticket 10: flight.status.changed / flight.gate.changed / flight.cancelled applied to a booking's flight snapshot
 * in every possible order give the same snapshot.
 */
class FlightSnapshotCommutativityTest {

    /** Events happen after the booking was created (its snapshot was read from flight-service "now"). */
    static final OffsetDateTime T1 = OffsetDateTime.now().plusMinutes(1);

    record Event(String name, String status, String gate, OffsetDateTime at) {}

    static Event statusChanged(String status, String gate, OffsetDateTime at) {
        return new Event("status " + status, status, gate, at);
    }
    static Event gateChanged(String gate, OffsetDateTime at) { return new Event("gate " + gate, null, gate, at); }

    @Test
    void statusAndGateChangesGiveTheSameSnapshotInAnyOrder() {
        List<Event> events = List.of(
                statusChanged("DELAYED", "A12", T1),
                gateChanged("B7", T1.plusMinutes(1)),
                statusChanged("BOARDING", "B7", T1.plusMinutes(2)),
                gateChanged("B9", T1.plusMinutes(3)));
        for (List<Event> order : permutations(events)) {
            Booking b = apply(order);
            assertThat(b.getFlightStatus()).as("order %s", names(order)).isEqualTo("BOARDING");
            assertThat(b.getGate()).as("order %s", names(order)).isEqualTo("B9");
        }
    }

    @Test
    void oldStatusChangeArrivingAfterNewerGateChangeStillSetsTheStatus() {
        // why status and gate have separate timestamps: one shared timestamp would drop the DELAYED status here
        Booking b = apply(List.of(gateChanged("B7", T1.plusMinutes(1)), statusChanged("DELAYED", null, T1)));
        assertThat(b.getFlightStatus()).isEqualTo("DELAYED");
        assertThat(b.getGate()).isEqualTo("B7");
    }

    @Test
    void cancelledFlightIsTerminalInAnyOrder() {
        List<Event> events = List.of(
                statusChanged("DELAYED", null, T1),
                statusChanged("CANCELLED", null, T1.plusMinutes(1)),
                // cannot happen in flight-service, but must not un-cancel
                statusChanged("BOARDING", null, T1.plusMinutes(2)));
        for (List<Event> order : permutations(events)) {
            assertThat(apply(order).getFlightStatus()).as("order %s", names(order)).isEqualTo("CANCELLED");
        }
    }

    @Test
    void eventOlderThanTheBookingDoesNotOverwriteWhatWasReadFromFlightService() {
        Booking b = newBooking();
        b.updateFlightSnapshot("SCHEDULED", "Z99", b.getCreatedAt().minusHours(1));
        assertThat(b.getGate()).isEqualTo("A12");
    }

    // ------------------------------------------------------------ helpers

    static Booking newBooking() {
        Passenger p = new Passenger("Anna", "Jensen", "anna@example.com", "P1234567", null);
        return new Booking("K7Q2ZP", p, 1L, "SK1501", OffsetDateTime.now().plusDays(1), "A12", "SCHEDULED",
                "12C", new BigDecimal("899.00"), "DKK");
    }

    static Booking apply(List<Event> order) {
        Booking b = newBooking();
        order.forEach(e -> b.updateFlightSnapshot(e.status(), e.gate(), e.at()));
        return b;
    }

    static List<String> names(List<Event> order) {
        return order.stream().map(Event::name).toList();
    }

    static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) return List.of(items);
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> order = new ArrayList<>();
                order.add(head);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }
}
