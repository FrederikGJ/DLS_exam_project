package dk.airport.notification;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ topology of the notification function. Declaring is idempotent, so both the one-off setup Job and
 * every function run declare it. The arguments must match what the services declare (durable, no extra args),
 * otherwise RabbitMQ refuses with PRECONDITION_FAILED.
 *
 * <pre>
 *  airport.events (topic) --booking.confirmed--+
 *                         --booking.cancelled--+--> notifications --(reject)--> airport.events.dlx --notification-job--> notification-job.dlq
 *                         --payment.refunded---+
 * </pre>
 */
public final class Topology {

    public static final String EXCHANGE = "airport.events";
    public static final String DEAD_LETTER_EXCHANGE = "airport.events.dlx";
    public static final String DEAD_LETTER_ROUTING_KEY = "notification-job";
    public static final String DEAD_LETTER_QUEUE = "notification-job.dlq";
    public static final List<String> ROUTING_KEYS = List.of("booking.confirmed", "booking.cancelled", "payment.refunded");

    private Topology() {}

    public static void declare(Channel channel, String queue) throws IOException {
        channel.exchangeDeclare(EXCHANGE, BuiltinExchangeType.TOPIC, true);
        channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, BuiltinExchangeType.DIRECT, true);

        channel.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, null);
        channel.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, DEAD_LETTER_ROUTING_KEY);

        channel.queueDeclare(queue, true, false, false, Map.of(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY));
        for (String key : ROUTING_KEYS) {
            channel.queueBind(queue, EXCHANGE, key);
        }
    }
}
