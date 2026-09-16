package dk.airport.notification.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * Stand-in for a mail provider: writes the mail to the log instead of sending it. The whole mail is one log statement,
 * so it stays together in {@code docker compose logs} and {@code kubectl logs}.
 */
public final class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(Mail mail) {
        log.info("Mail (simulated, not sent) eventType={} eventId={}\n"
                        + "  Til:  {}\n"
                        + "  Emne: {}\n"
                        + "{}",
                mail.eventType(), mail.eventId(), mail.to(), mail.subject(), indent(mail.body()));
    }

    private static String indent(String body) {
        return body.lines()
                .map(line -> line.isEmpty() ? "  |" : "  | " + line)
                .collect(Collectors.joining("\n"));
    }
}
