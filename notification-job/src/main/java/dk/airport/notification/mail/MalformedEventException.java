package dk.airport.notification.mail;

/**
 * The message cannot be turned into a mail: not JSON, no envelope, or a required payload field is missing or invalid.
 * Retrying cannot fix that, so the job rejects the message without requeue and RabbitMQ moves it to the dead-letter
 * queue.
 */
public class MalformedEventException extends Exception {

    private static final long serialVersionUID = 1L;

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
