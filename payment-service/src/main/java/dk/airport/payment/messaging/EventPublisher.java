package dk.airport.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

/**
 * Publishes events wrapped in {@link EventEnvelope} to the topic exchange, using the event type as routing key.
 * The RabbitTemplate is channel-transacted, so when called inside a DB transaction the message is only
 * sent once the transaction commits.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String producer;

    public EventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
                          @Value("${app.messaging.exchange}") String exchange,
                          @Value("${app.messaging.producer}") String producer) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.producer = producer;
    }

    public EventEnvelope publish(String eventType, Object payload) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                producer,
                objectMapper.valueToTree(payload));
        try {
            byte[] body = objectMapper.writeValueAsBytes(envelope);
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setContentEncoding(StandardCharsets.UTF_8.name());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setMessageId(envelope.eventId());
            props.setType(eventType);
            props.setTimestamp(new Date());
            rabbitTemplate.send(exchange, eventType, new Message(body, props));
            log.info("Published event {} eventId={}", eventType, envelope.eventId());
            return envelope;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event " + eventType, e);
        }
    }
}
