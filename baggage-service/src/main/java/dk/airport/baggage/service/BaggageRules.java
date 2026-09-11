package dk.airport.baggage.service;

import dk.airport.baggage.domain.ApiException;
import dk.airport.baggage.domain.BaggageType;
import dk.airport.baggage.domain.ErrorCode;

import java.math.BigDecimal;
import java.util.Set;

/** Pure, unit-testable domain rules for baggage registration. */
public final class BaggageRules {

    public static final int MAX_CHECKED_PER_BOOKING = 3;
    public static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("32");
    public static final Set<String> ELIGIBLE_BOOKING_STATUSES = Set.of("CONFIRMED", "CHECKED_IN");

    private BaggageRules() {}

    /** Baggage may only be registered on a CONFIRMED or CHECKED_IN booking. */
    public static void assertEligible(String bookingStatus) {
        if (bookingStatus == null || !ELIGIBLE_BOOKING_STATUSES.contains(bookingStatus)) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "Booking must be CONFIRMED or CHECKED_IN to register baggage (current status: " + bookingStatus + ")");
        }
    }

    /** 0 &lt; weight &lt;= 32 kg. */
    public static void assertWeight(BigDecimal weightKg) {
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "weightKg must be greater than 0");
        }
        if (weightKg.compareTo(MAX_WEIGHT_KG) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "weightKg must not exceed " + MAX_WEIGHT_KG + " kg per piece (was " + weightKg.stripTrailingZeros().toPlainString() + ")");
        }
    }

    /** Max 3 CHECKED bags per booking. CABIN and SPECIAL are not limited by this rule. */
    public static void assertCheckedLimit(long currentCheckedCount, BaggageType type) {
        if (type == BaggageType.CHECKED && currentCheckedCount >= MAX_CHECKED_PER_BOOKING) {
            throw new ApiException(ErrorCode.BAGGAGE_LIMIT_EXCEEDED,
                    "A booking may have at most " + MAX_CHECKED_PER_BOOKING + " CHECKED bags");
        }
    }
}
