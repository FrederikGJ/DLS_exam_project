package dk.airport.booking.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the messaging topology used by booking-service:
 * <pre>
 *  airport.events (topic)  --payment.#-->  booking-service.payment-events
 *                          --flight.#-->   booking-service.flight-events
 *                                            | (rejected after 3 attempts)
 *                                            v
 *  airport.events.dlx (direct) --booking-service--> booking-service.dlq
 *
 *  airport.events (topic)  --booking.#-->  notifications                       (consumed by notification-job)
 *                                            | (malformed event, rejected by the job)
 *                                            v
 *  airport.events.dlx (direct) --notification-job--> notification-job.dlq
 * </pre>
 * The {@code notifications} queue belongs to notification-job, which declares it itself on every run. booking-service
 * declares it as well so booking events are kept from the moment the service starts, even if the job has never run
 * (a message published to a topic exchange without a matching queue is dropped). RabbitMQ only accepts the second
 * declaration if it is identical - durable, same dead-letter arguments - so this must match
 * notification-job's {@code Topology} exactly; a mismatch makes whichever side declares second fail with
 * PRECONDITION_FAILED.
 */
@Configuration
public class RabbitConfig {

    @Value("${app.messaging.exchange}")
    private String exchangeName;
    @Value("${app.messaging.dead-letter-exchange}")
    private String deadLetterExchangeName;
    @Value("${app.messaging.producer}")
    private String serviceName;
    @Value("${app.messaging.queues.payment-events}")
    private String paymentEventsQueue;
    @Value("${app.messaging.queues.flight-events}")
    private String flightEventsQueue;
    @Value("${app.messaging.queues.dead-letter}")
    private String deadLetterQueue;
    @Value("${app.messaging.queues.notifications}")
    private String notificationsQueue;
    @Value("${app.messaging.queues.notifications-dead-letter}")
    private String notificationsDeadLetterQueue;
    @Value("${app.messaging.notification-consumer}")
    private String notificationConsumer;

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
    public Queue paymentEventsQueue() {
        return QueueBuilder.durable(paymentEventsQueue)
                .deadLetterExchange(deadLetterExchangeName)
                .deadLetterRoutingKey(serviceName)
                .build();
    }

    @Bean
    public Binding paymentEventsBinding() {
        return BindingBuilder.bind(paymentEventsQueue()).to(airportEventsExchange()).with("payment.#");
    }

    @Bean
    public Queue flightEventsQueue() {
        return QueueBuilder.durable(flightEventsQueue)
                .deadLetterExchange(deadLetterExchangeName)
                .deadLetterRoutingKey(serviceName)
                .build();
    }

    @Bean
    public Binding flightEventsBinding() {
        return BindingBuilder.bind(flightEventsQueue()).to(airportEventsExchange()).with("flight.#");
    }

    // ---- notification-job's queue (dev plan DP-20), see the class comment

    @Bean
    public Queue notificationsDeadLetterQueue() {
        return QueueBuilder.durable(notificationsDeadLetterQueue).build();
    }

    @Bean
    public Binding notificationsDeadLetterBinding() {
        return BindingBuilder.bind(notificationsDeadLetterQueue()).to(deadLetterExchange()).with(notificationConsumer);
    }

    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(notificationsQueue)
                .deadLetterExchange(deadLetterExchangeName)
                .deadLetterRoutingKey(notificationConsumer)
                .build();
    }

    @Bean
    public Binding notificationsBinding() {
        return BindingBuilder.bind(notificationsQueue()).to(airportEventsExchange()).with("booking.#");
    }
}
