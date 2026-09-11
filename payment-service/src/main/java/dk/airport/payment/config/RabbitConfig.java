package dk.airport.payment.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the messaging topology used by payment-service:
 * <pre>
 *  airport.events (topic)  --booking.#-->  payment-service.booking-events
 *                                            | (rejected after 3 attempts)
 *                                            v
 *  airport.events.dlx (direct) --payment-service--> payment-service.dlq
 * </pre>
 */
@Configuration
public class RabbitConfig {

    @Value("${app.messaging.exchange}")
    private String exchangeName;
    @Value("${app.messaging.dead-letter-exchange}")
    private String deadLetterExchangeName;
    @Value("${app.messaging.producer}")
    private String serviceName;
    @Value("${app.messaging.queues.booking-events}")
    private String bookingEventsQueue;
    @Value("${app.messaging.queues.dead-letter}")
    private String deadLetterQueue;

    @Bean
    public TopicExchange airportEventsExchange() {
        return ExchangeBuilder.topicExchange(exchangeName).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(deadLetterExchangeName).durable(true).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueue).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(serviceName);
    }

    @Bean
    public Queue bookingEventsQueue() {
        return QueueBuilder.durable(bookingEventsQueue)
                .deadLetterExchange(deadLetterExchangeName)
                .deadLetterRoutingKey(serviceName)
                .build();
    }

    @Bean
    public Binding bookingEventsBinding() {
        return BindingBuilder.bind(bookingEventsQueue()).to(airportEventsExchange()).with("booking.#");
    }

    /** Synchronise publishing with the surrounding DB transaction (publish after commit). */
    @Bean
    public RabbitTemplateCustomizer transactedRabbitTemplate() {
        return (RabbitTemplate template) -> template.setChannelTransacted(true);
    }
}
