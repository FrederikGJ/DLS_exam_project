package dk.airport.baggage.service;

import dk.airport.baggage.domain.ApiException;
import dk.airport.baggage.domain.BaggageType;
import dk.airport.baggage.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaggageRulesTest {

    // ---------------------------------------------------------- eligibility

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "CHECKED_IN"})
    void confirmedAndCheckedInBookingsAreEligible(String status) {
        assertThatCode(() -> BaggageRules.assertEligible(status)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "CANCELLED", "nonsense"})
    void otherBookingStatusesAreRejected(String status) {
        assertThatThrownBy(() -> BaggageRules.assertEligible(status))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void nullStatusIsRejected() {
        assertThatThrownBy(() -> BaggageRules.assertEligible(null)).isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------- weight

    @Test
    void exactlyThirtyTwoKgIsAllowed() {
        assertThatCode(() -> BaggageRules.assertWeight(new BigDecimal("32.0"))).doesNotThrowAnyException();
        assertThatCode(() -> BaggageRules.assertWeight(new BigDecimal("32"))).doesNotThrowAnyException();
    }

    @Test
    void justOverThirtyTwoKgIsRejected() {
        assertThatThrownBy(() -> BaggageRules.assertWeight(new BigDecimal("32.01")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void zeroNegativeAndNullWeightAreRejected() {
        assertThatThrownBy(() -> BaggageRules.assertWeight(BigDecimal.ZERO)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BaggageRules.assertWeight(new BigDecimal("-1"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BaggageRules.assertWeight(null)).isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------- checked limit

    @Test
    void thirdCheckedBagIsAllowed() {
        assertThatCode(() -> BaggageRules.assertCheckedLimit(2, BaggageType.CHECKED)).doesNotThrowAnyException();
    }

    @Test
    void fourthCheckedBagIsRejected() {
        assertThatThrownBy(() -> BaggageRules.assertCheckedLimit(3, BaggageType.CHECKED))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.BAGGAGE_LIMIT_EXCEEDED);
    }

    @Test
    void cabinAndSpecialBagsAreNotLimitedByCheckedRule() {
        assertThatCode(() -> BaggageRules.assertCheckedLimit(10, BaggageType.CABIN)).doesNotThrowAnyException();
        assertThatCode(() -> BaggageRules.assertCheckedLimit(10, BaggageType.SPECIAL)).doesNotThrowAnyException();
    }
}
