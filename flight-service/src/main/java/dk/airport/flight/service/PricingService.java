package dk.airport.flight.service;

import dk.airport.flight.domain.SeatClass;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure domain logic: seat price = flight base price * class multiplier, rounded to 2 decimals. */
@Service
public class PricingService {

    public BigDecimal seatPrice(BigDecimal basePrice, SeatClass seatClass) {
        if (basePrice == null || basePrice.signum() < 0) {
            throw new IllegalArgumentException("basePrice must be >= 0");
        }
        return basePrice.multiply(seatClass.priceMultiplier()).setScale(2, RoundingMode.HALF_UP);
    }
}
