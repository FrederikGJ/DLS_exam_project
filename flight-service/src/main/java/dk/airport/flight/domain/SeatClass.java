package dk.airport.flight.domain;

import java.math.BigDecimal;

public enum SeatClass {
    ECONOMY(new BigDecimal("1.00")),
    BUSINESS(new BigDecimal("2.50")),
    FIRST(new BigDecimal("4.00"));

    private final BigDecimal priceMultiplier;

    SeatClass(BigDecimal priceMultiplier) {
        this.priceMultiplier = priceMultiplier;
    }

    public BigDecimal priceMultiplier() {
        return priceMultiplier;
    }
}
