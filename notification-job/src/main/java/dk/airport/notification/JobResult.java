package dk.airport.notification;

/**
 * What one run did. {@code sent + skipped + rejected == received}.
 *
 * @param received deliveries taken from the queue
 * @param sent mails rendered and handed to the MailSender (acknowledged)
 * @param skipped events without a mail template, e.g. a future booking.x.v2 (acknowledged)
 * @param rejected malformed or unrenderable events, rejected without requeue (now in notification-job.dlq)
 * @param stopReason why the loop ended
 */
public record JobResult(int received, int sent, int skipped, int rejected, StopReason stopReason) {

    /** Both reasons are a normal end of a run: the job exits with 0. */
    public enum StopReason {
        /** No delivery arrived within IDLE_TIMEOUT_MS: the queue is drained. */
        IDLE,
        /** MAX_MESSAGES deliveries were handled; the rest is left for the next run. */
        MAX_MESSAGES
    }
}
