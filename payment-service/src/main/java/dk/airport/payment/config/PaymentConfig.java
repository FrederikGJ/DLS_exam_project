package dk.airport.payment.config;

import dk.airport.payment.service.PaymentSimulator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PaymentConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PaymentSimulator paymentSimulator(Clock clock) {
        return new PaymentSimulator(clock);
    }
}
