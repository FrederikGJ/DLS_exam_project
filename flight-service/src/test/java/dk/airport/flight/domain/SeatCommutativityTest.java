package dk.airport.flight.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ticket 10: booking.confirmed / booking.cancelled applied to a seat in every possible order give the same end state.
 */
class SeatCommutativityTest {

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-19T10:00:00Z");
    static final OffsetDateTime T2 = T1.plusMinutes(1);
    static final OffsetDateTime T3 = T1.plusMinutes(2);

    record Event(String name, Consumer<Seat> apply) {}

    static Event confirmed(String ref, OffsetDateTime at) { return new Event(ref + " confirmed", s -> s.takeFor(ref, at)); }
    static Event cancelled(String ref, OffsetDateTime at) { return new Event(ref + " cancelled", s -> s.releaseFor(ref, at)); }

    @Test
    void confirmedThenCancelledInAnyOrderFreesTheSeat() {
        List<Event> events = List.of(confirmed("AAAAAA", T1), cancelled("AAAAAA", T2));
        for (List<Event> order : permutations(events)) {
            Seat seat = apply(order);
            assertThat(seat.isAvailable()).as("order %s", names(order)).isTrue();
        }
    }

    @Test
    void cancelledArrivingBeforeConfirmedStillEndsFree() {
        // the classic bug: booking.cancelled before booking.confirmed used to end as "taken"
        Seat seat = apply(List.of(cancelled("AAAAAA", T2), confirmed("AAAAAA", T1)));
        assertThat(seat.isAvailable()).isTrue();
        assertThat(seat.getHeldByBooking()).isEqualTo("AAAAAA");
    }

    @Test
    void rebookedSeatEndsTakenByTheNewBookingInAnyOrder() {
        // A books and cancels, then B books the same seat
        List<Event> events = List.of(confirmed("AAAAAA", T1), cancelled("AAAAAA", T2), confirmed("BBBBBB", T3));
        for (List<Event> order : permutations(events)) {
            Seat seat = apply(order);
            assertThat(seat.isAvailable()).as("order %s", names(order)).isFalse();
            assertThat(seat.getHeldByBooking()).as("order %s", names(order)).isEqualTo("BBBBBB");
            assertThat(seat.getAvailabilityEventAt()).as("order %s", names(order)).isEqualTo(T3);
        }
    }

    @Test
    void lateCancelOfAnOldBookingDoesNotFreeAnotherBookingsSeat() {
        // ticket 11: a late payment makes booking-service publish booking.cancelled for A again, after B took the seat
        Seat seat = apply(List.of(confirmed("AAAAAA", T1), cancelled("AAAAAA", T2), confirmed("BBBBBB", T3),
                cancelled("AAAAAA", T3.plusMinutes(5))));
        assertThat(seat.isAvailable()).isFalse();
        assertThat(seat.getHeldByBooking()).isEqualTo("BBBBBB");
    }

    @Test
    void duplicateConfirmedIsANoOp() {
        Seat seat = new Seat(null, "5C", SeatClass.ECONOMY);
        assertThat(seat.takeFor("AAAAAA", T1)).isTrue();
        assertThat(seat.takeFor("AAAAAA", T1)).isFalse();
        assertThat(seat.isAvailable()).isFalse();
    }

    // ------------------------------------------------------------ helpers

    static Seat apply(List<Event> order) {
        Seat seat = new Seat(null, "5C", SeatClass.ECONOMY);
        order.forEach(e -> e.apply().accept(seat));
        return seat;
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
