package io.saasforge.iam.infrastructure.mail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.application.authentication.PasswordSetupMail;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SmtpPasswordSetupMailerIT {
    private static final String TOKEN = "C".repeat(43);

    @Container
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            DockerImageName.parse("axllent/mailpit:v1.30.7"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));

    @Test
    void mailpitAcceptsTheFragmentLinkWithoutUnrelatedSecrets() throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(MAILPIT.getHost());
        sender.setPort(MAILPIT.getMappedPort(1025));
        SmtpPasswordSetupMailer mailer = new SmtpPasswordSetupMailer(sender, "no-reply@saasforge.test");

        mailer.send(new PasswordSetupMail(
                "tenant-admin@example.test",
                URI.create("https://console.saasforge.test/password-setup#token=" + TOKEN)));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":"
                        + MAILPIT.getMappedPort(8025) + "/api/v1/message/latest")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("tenant-admin@example.test"));
        assertTrue(response.body().contains("https://console.saasforge.test/password-setup#token=" + TOKEN));
        assertFalse(response.body().contains("SMTP_PASSWORD"));
        assertFalse(response.body().contains("newPassword"));
    }
}
