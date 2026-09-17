package dk.airport.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets Hibernate write JSON columns ({@code @JdbcTypeCode(SqlTypes.JSON)}) with Spring's ObjectMapper instead of a
 * plain one of its own. The difference that matters: timestamps in {@code booking_overview.payments}/{@code baggage}
 * are stored as ISO-8601 text ({@code "2026-09-17T10:15:30.123Z"}) rather than epoch numbers, so the read model is
 * readable in pgAdmin/psql.
 */
@Configuration
public class JpaJsonConfig {

    @Bean
    HibernatePropertiesCustomizer jsonFormatMapper(ObjectMapper objectMapper) {
        return properties -> properties.put(AvailableSettings.JSON_FORMAT_MAPPER,
                new JacksonJsonFormatMapper(objectMapper));
    }
}
