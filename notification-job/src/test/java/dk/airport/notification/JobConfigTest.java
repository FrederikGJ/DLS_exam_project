package dk.airport.notification;

import dk.airport.notification.mail.Mail;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobConfigTest {

    @Test
    void emptyEnvironment_usesTheLocalDevelopmentDefaults() {
        JobConfig config = JobConfig.fromEnv(Map.of());

        assertThat(config).isEqualTo(new JobConfig("localhost", 5672, "airport", "airport", "notifications", 100,
                Duration.ofMillis(5000)));
    }

    @Test
    void everyValueCanBeOverridden_blankMeansDefault() {
        JobConfig config = JobConfig.fromEnv(Map.of(
                "RABBITMQ_HOST", "rabbitmq",
                "RABBITMQ_PORT", " 5673 ",
                "RABBITMQ_USERNAME", "job",
                "RABBITMQ_PASSWORD", "s3cret",
                "NOTIFICATION_QUEUE", "  ",
                "MAX_MESSAGES", "10",
                "IDLE_TIMEOUT_MS", "250"));

        assertThat(config).isEqualTo(new JobConfig("rabbitmq", 5673, "job", "s3cret", "notifications", 10,
                Duration.ofMillis(250)));
    }

    @Test
    void invalidNumbers_areRejectedWithTheVariableName() {
        assertThatThrownBy(() -> JobConfig.fromEnv(Map.of("MAX_MESSAGES", "many")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MAX_MESSAGES");
        assertThatThrownBy(() -> JobConfig.fromEnv(Map.of("MAX_MESSAGES", "0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MAX_MESSAGES");
        assertThatThrownBy(() -> JobConfig.fromEnv(Map.of("RABBITMQ_PORT", "70000")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RABBITMQ_PORT");
        assertThatThrownBy(() -> JobConfig.fromEnv(Map.of("IDLE_TIMEOUT_MS", "-1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDLE_TIMEOUT_MS");
    }

    @Test
    void toString_doesNotContainThePassword() {
        JobConfig config = JobConfig.fromEnv(Map.of("RABBITMQ_PASSWORD", "s3cret"));

        assertThat(config.toString()).doesNotContain("s3cret").contains("queue=notifications");
    }

    @Test
    void invalidConfiguration_exitsWithTwo_withoutConnecting() {
        List<Mail> mails = new ArrayList<>();

        int exitCode = Main.run(Map.of("MAX_MESSAGES", "abc"), mails::add);

        assertThat(exitCode).isEqualTo(Main.EXIT_INVALID_CONFIG);
        assertThat(mails).isEmpty();
    }
}
