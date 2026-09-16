package dk.airport.notification.mail;

/**
 * Delivers a rendered mail. The job only ships {@link LoggingMailSender}; a real provider (SMTP, SendGrid, ...)
 * would implement this interface and pass {@link Mail#eventId()} as idempotency key.
 * <p>
 * A failure must be thrown, not swallowed: the job then stops without acknowledging the message, RabbitMQ puts it
 * back in the queue, and the next run tries again.
 */
public interface MailSender {

    /**
     * Sends one mail.
     *
     * @param mail the rendered mail
     */
    void send(Mail mail);
}
