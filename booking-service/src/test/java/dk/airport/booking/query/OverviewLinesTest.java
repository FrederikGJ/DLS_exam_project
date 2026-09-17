package dk.airport.booking.query;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BinaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The guard of the read model (dev plan DP-28): a payment or bag line ends up the same no matter in which order its
 * events arrive or how often one of them is applied.
 */
class OverviewLinesTest {

    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-17T10:00:00Z");
    static final OffsetDateTime T2 = T1.plusSeconds(30);
    static final OffsetDateTime T3 = T1.plusSeconds(60);

    static final OverviewPayment COMPLETED = new OverviewPayment(7L, "COMPLETED", new BigDecimal("899.00"), "DKK",
            "4242", null, T1, T1);
    static final OverviewPayment REFUNDED = new OverviewPayment(7L, "REFUNDED", new BigDecimal("899.00"), "DKK",
            null, null, null, T2);

    static final OverviewBaggage REGISTERED = new OverviewBaggage("BAG-1", "CHECKED", new BigDecimal("23.0"),
            "REGISTERED", "CHECK_IN", T1, T1);
    static final OverviewBaggage LOADED = new OverviewBaggage("BAG-1", null, null, "LOADED", "Belt 4", null, T2);
    static final OverviewBaggage IN_TRANSIT = new OverviewBaggage("BAG-1", null, null, "IN_TRANSIT", "Plane", null, T3);

    @Test
    void refundedPaymentKeepsCardDigitsAndCreationTimeFromTheCompletedEvent() {
        OverviewPayment merged = COMPLETED.merge(REFUNDED);

        assertThat(merged).isEqualTo(new OverviewPayment(7L, "REFUNDED", new BigDecimal("899.00"), "DKK", "4242",
                null, T1, T2));
    }

    @Test
    void paymentEventsGiveTheSameLineInEitherOrder() {
        assertThat(REFUNDED.merge(COMPLETED)).isEqualTo(COMPLETED.merge(REFUNDED));
    }

    @Test
    void aLateOlderPaymentEventDoesNotReopenARefund() {
        OverviewPayment merged = COMPLETED.merge(REFUNDED).merge(COMPLETED);

        assertThat(merged.status()).isEqualTo("REFUNDED");
    }

    @Test
    void applyingTheSameEventTwiceChangesNothing() {
        assertThat(COMPLETED.merge(COMPLETED)).isEqualTo(COMPLETED);
        assertThat(LOADED.merge(LOADED)).isEqualTo(LOADED);
    }

    @Test
    void bagEndsInTheNewestStatusWithTypeAndWeightFromRegistration_forEveryArrivalOrder() {
        OverviewBaggage expected = new OverviewBaggage("BAG-1", "CHECKED", new BigDecimal("23.0"), "IN_TRANSIT",
                "Plane", T1, T3);

        for (List<OverviewBaggage> order : permutations(List.of(REGISTERED, LOADED, IN_TRANSIT))) {
            assertThat(fold(order, OverviewBaggage::merge)).as("order %s", order).isEqualTo(expected);
        }
    }

    @Test
    void equalTimestampsAreResolvedTheSameWayInBothOrders() {
        OverviewBaggage security = new OverviewBaggage("BAG-1", null, null, "SECURITY", "Scanner 2", null, T2);

        assertThat(LOADED.merge(security)).isEqualTo(security.merge(LOADED));
    }

    @Test
    void overviewKeepsOneLinePerPaymentAndBagSortedByTime() {
        BookingOverview overview = new BookingOverview(1L);
        OverviewPayment failed = new OverviewPayment(6L, "FAILED", new BigDecimal("899.00"), "DKK", "0000",
                "Insufficient funds", T1.minusMinutes(5), T1.minusMinutes(5));

        overview.mergePayment(REFUNDED);
        overview.mergePayment(COMPLETED);
        overview.mergePayment(failed);
        overview.mergeBaggage(LOADED);
        overview.mergeBaggage(REGISTERED);

        assertThat(overview.getPayments()).extracting(OverviewPayment::paymentId, OverviewPayment::status)
                .containsExactly(tuple(6L, "FAILED"), tuple(7L, "REFUNDED"));
        assertThat(overview.getBaggage()).singleElement()
                .satisfies(b -> assertThat(b.status()).isEqualTo("LOADED"))
                .satisfies(b -> assertThat(b.weightKg()).isEqualByComparingTo("23.0"));
        assertThat(overview.getProjectedAt()).isNotNull();
    }

    private static <T> T fold(List<T> events, BinaryOperator<T> merge) {
        return events.stream().reduce(merge).orElseThrow();
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> permutation = new ArrayList<>(Collections.singletonList(head));
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }
}
