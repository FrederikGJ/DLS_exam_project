package dk.airport.flight.service;

import dk.airport.flight.domain.SeatClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingServiceTest {

    private final PricingService pricing = new PricingService();

    @Test
    void economySeatCostsBasePrice() {
        assertThat(pricing.seatPrice(new BigDecimal("899"), SeatClass.ECONOMY)).isEqualByComparingTo("899.00");
    }

    @Test
    void businessSeatCostsTwoAndAHalfTimesBase() {
        assertThat(pricing.seatPrice(new BigDecimal("899.00"), SeatClass.BUSINESS)).isEqualByComparingTo("2247.50");
    }

    @Test
    void firstSeatCostsFourTimesBase() {
        assertThat(pricing.seatPrice(new BigDecimal("899.00"), SeatClass.FIRST)).isEqualByComparingTo("3596.00");
    }

    @Test
    void priceIsRoundedHalfUpToTwoDecimals() {
        // 333.33 * 2.5 = 833.325 -> 833.33
        assertThat(pricing.seatPrice(new BigDecimal("333.33"), SeatClass.BUSINESS)).isEqualTo(new BigDecimal("833.33"));
    }

    @Test
    void negativeBasePriceIsRejected() {
        assertThatThrownBy(() -> pricing.seatPrice(new BigDecimal("-1"), SeatClass.ECONOMY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
