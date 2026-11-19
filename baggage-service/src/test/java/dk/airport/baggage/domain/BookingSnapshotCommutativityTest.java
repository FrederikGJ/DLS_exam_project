package dk.airport.baggage.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ticket 10: booking.* events applied to the baggage booking_snapshot in every possible order give the same status.
 * Mirrors what BookingSnapshotService.upsert does: create the row from the first event, update it with the rest.
 */
class BookingSnapshotCommutativityTest {

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-19T10:00:00Z");

    record Event(String status, OffsetDateTime at) {}

    @Test
    void createdConfirmedCheckedInEndsCheckedInInAnyOrder() {
        List<Event> events = List.of(new Event("PENDING_PAYMENT", T1), new Event("CONFIRMED", T1.plusMinutes(1)),
                new Event("CHECKED_IN", T1.plusMinutes(2)));
        for (List<Event> order : permutations(events)) {
            assertThat(apply(order).getStatus()).as("order %s", order).isEqualTo("CHECKED_IN");
        }
    }

    @Test
    void cancelledWinsOverConfirmedInAnyOrder() {
        List<Event> events = List.of(new Event("PENDING_PAYMENT", T1), new Event("CONFIRMED", T1.plusMinutes(1)),
                new Event("CANCELLED", T1.plusMinutes(2)));
        for (List<Event> order : permutations(events)) {
            assertThat(apply(order).getStatus()).as("order %s", order).isEqualTo("CANCELLED");
        }
    }

    @Test
    void cancelledIsTerminalEvenWhenItIsOlder() {
        // booking.cancelled before booking.confirmed used to end as CONFIRMED (last write wins)
        BookingSnapshot s = apply(List.of(new Event("CANCELLED", T1), new Event("CONFIRMED", T1.plusMinutes(5))));
        assertThat(s.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void olderEventDoesNotOverwriteNewerStatus() {
        BookingSnapshot s = apply(List.of(new Event("CONFIRMED", T1.plusMinutes(1)), new Event("PENDING_PAYMENT", T1)));
        assertThat(s.getStatus()).isEqualTo("CONFIRMED");
        assertThat(s.getEventOccurredAt()).isEqualTo(T1.plusMinutes(1));
    }

    // ------------------------------------------------------------ helpers

    static BookingSnapshot apply(List<Event> order) {
        BookingSnapshot snapshot = null;
        for (Event e : order) {
            if (snapshot == null) {
                snapshot = new BookingSnapshot("K7Q2ZP", "Anna Jensen", "SK1501", 1L, e.status(), e.at());
            } else {
                snapshot.update("Anna Jensen", "SK1501", 1L, e.status(), e.at());
            }
        }
        return snapshot;
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
