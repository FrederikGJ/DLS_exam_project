package dk.airport.notification;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.util.Map;

/**
 * Declares the messaging topology the job consumes from:
 * <pre>
 *  airport.events (topic)  --booking.#-->  notifications
 *                                            | nack(requeue=false): malformed or unrenderable event
 *                                            v
 *  airport.events.dlx (direct) --notification-job--> notification-job.dlq
 * </pre>
 * Every declaration is idempotent: RabbitMQ accepts a re-declaration when type, flags and arguments are identical,
 * so the job declares everything on every start. booking-service declares the same queue, dead-letter queue and
 * bindings (booking-service RabbitConfig) so booking events are kept even before the job has ever run. If the two
 * ever differ, the broker refuses the second declaration with PRECONDITION_FAILED (406) and the job exits with 1 -
 * any change here has to be made there as well.
 */
public final class Topology {

    public static final String EXCHANGE = "airport.events";
    public static final String DEAD_LETTER_EXCHANGE = "airport.events.dlx";
    /** Routing key on the dead-letter exchange; by convention the consumer's name (see docs/events.md). */
    public static final String DEAD_LETTER_ROUTING_KEY = "notification-job";
    public static final String DEAD_LETTER_QUEUE = "notification-job.dlq";
    /** All booking events. {@code #} and not {@code *}, so a future {@code booking.x.v2} still arrives. */
    public static final String BINDING_PATTERN = "booking.#";

    private Topology() {}

    /**
     * Declares exchanges, queues and bindings. Safe to call any number of times.
     *
     * @param channel an open channel; it is closed by the broker if a declaration conflicts with an existing one
     * @param queue name of the work queue (NOTIFICATION_QUEUE)
     * @throws IOException if the broker rejects a declaration or the connection fails
     */
    public static void declare(Channel channel, String queue) throws IOException {
        channel.exchangeDeclare(EXCHANGE, BuiltinExchangeType.TOPIC, true);
        channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, BuiltinExchangeType.DIRECT, true);

        channel.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, null);
        channel.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, DEAD_LETTER_ROUTING_KEY);

        channel.queueDeclare(queue, true, false, false, queueArguments());
        channel.queueBind(queue, EXCHANGE, BINDING_PATTERN);
    }

    /** Arguments of the work queue - exactly what booking-service declares. */
    static Map<String, Object> queueArguments() {
        return Map.of(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
    }
}
