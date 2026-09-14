package dk.airport.flight.config;

import dk.airport.flight.messaging.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the scheduled {@code OutboxRelay} and binds its {@code app.outbox.*} settings. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
}
