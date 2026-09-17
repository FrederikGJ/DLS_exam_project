package dk.airport.booking.query;

import java.time.OffsetDateTime;
import java.util.function.Function;

/** Merge helpers shared by {@link OverviewPayment} and {@link OverviewBaggage}. */
final class Lines {

    private Lines() {}

    /**
     * The observation with the later timestamp. On an exact tie the one with the greater tie-break key wins, so the
     * choice is the same whichever of the two arrived first (commutative merge).
     */
    static <T> T newer(T a, T b, Function<T, OffsetDateTime> timestamp, Function<T, String> tieBreak) {
        OffsetDateTime ta = timestamp.apply(a);
        OffsetDateTime tb = timestamp.apply(b);
        if (ta.isBefore(tb)) {
            return b;
        }
        if (tb.isBefore(ta)) {
            return a;
        }
        return String.valueOf(tieBreak.apply(a)).compareTo(String.valueOf(tieBreak.apply(b))) >= 0 ? a : b;
    }

    static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    static OffsetDateTime earliest(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.isBefore(a) ? b : a;
    }
}
