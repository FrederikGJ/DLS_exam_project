package dk.airport.notification;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;
import dk.airport.notification.mail.LoggingMailSender;
import dk.airport.notification.mail.MailRenderer;
import dk.airport.notification.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Entry point of notification-job (dev plan DP-20). Runs to completion: connect, drain the queue, exit.
 * <pre>
 *  exit 0  the queue was drained (idle timeout) or MAX_MESSAGES were handled
 *  exit 1  RabbitMQ failure: unreachable, login refused, a declaration refused, connection lost, mail sender failed
 *  exit 2  invalid configuration (see {@link JobConfig})
 * </pre>
 * A non-zero exit leaves every unacknowledged message in the queue; docker compose shows the exit code, and in
 * Kubernetes the Job's backoff policy starts a new attempt (KEDA ScaledJob, dev plan DP-21).
 */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_BROKER_FAILURE = 1;
    static final int EXIT_INVALID_CONFIG = 2;

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int CONNECTION_TIMEOUT_MS = 10_000;
    private static final int CLOSE_TIMEOUT_MS = 5_000;

    private Main() {}

    /**
     * Runs the job with the process environment and exits with its exit code.
     *
     * @param args ignored; all configuration comes from environment variables
     */
    public static void main(String[] args) {
        System.exit(run(System.getenv(), new LoggingMailSender()));
    }

    /**
     * One complete run. Separate from {@link #main(String[])} so tests can pass an environment and a recording sender.
     *
     * @param env environment variables (see {@link JobConfig})
     * @param sender where rendered mails go
     * @return the process exit code
     */
    static int run(Map<String, String> env, MailSender sender) {
        JobConfig config;
        try {
            config = JobConfig.fromEnv(env);
        } catch (IllegalArgumentException e) {
            log.error("Invalid configuration: {}", e.getMessage());
            return EXIT_INVALID_CONFIG;
        }
        log.info("notification-job starting: {}", config);

        Connection connection = null;
        try {
            connection = connectionFactory(config).newConnection("notification-job");
            JobResult result = new NotificationJob(config, new MailRenderer(), sender).run(connection);
            log.info("notification-job done ({}): received={} sent={} skipped={} rejected={}",
                    result.stopReason(), result.received(), result.sent(), result.skipped(), result.rejected());
            return EXIT_OK;
        } catch (IOException | TimeoutException | ShutdownSignalException e) {
            log.error("notification-job failed against RabbitMQ {}:{}: {}", config.host(), config.port(), describe(e));
            return EXIT_BROKER_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("notification-job interrupted");
            return EXIT_BROKER_FAILURE;
        } catch (RuntimeException e) {
            log.error("notification-job failed, unacknowledged messages stay in the queue", e);
            return EXIT_BROKER_FAILURE;
        } finally {
            closeQuietly(connection);
        }
    }

    private static ConnectionFactory connectionFactory(JobConfig config) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.host());
        factory.setPort(config.port());
        factory.setUsername(config.username());
        factory.setPassword(config.password());
        factory.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        // A job does not reconnect: it exits non-zero, the message stays in the queue, and the next run picks it up.
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        return factory;
    }

    /** The exception plus its cause, e.g. "IOException caused by ShutdownSignalException: ... PRECONDITION_FAILED". */
    private static String describe(Exception e) {
        Throwable cause = e.getCause();
        return cause == null || cause == e ? e.toString() : e + " caused by " + cause;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null || !connection.isOpen()) {
            return;
        }
        try {
            connection.close(CLOSE_TIMEOUT_MS);
        } catch (IOException | ShutdownSignalException e) {
            log.warn("Could not close the RabbitMQ connection cleanly: {}", e.toString());
        }
    }
}
