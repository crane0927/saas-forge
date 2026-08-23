package io.saasforge.iam.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.application.authentication.PasswordSetupMailer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PasswordSetupMailConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PasswordSetupMailConfiguration.class);

    @Test
    void productionRequiresExplicitAuthenticatedStartTlsSmtpAndPageUri() {
        runner.withPropertyValues("saasforge.environment=prod")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues(
                        "saasforge.environment=prod",
                        "saasforge.iam.password-setup.smtp.host=smtp.example.test",
                        "saasforge.iam.password-setup.smtp.port=587",
                        "saasforge.iam.password-setup.smtp.username=mailer",
                        "saasforge.iam.password-setup.smtp.password=secret-value",
                        "saasforge.iam.password-setup.smtp.from=no-reply@example.test",
                        "saasforge.iam.password-setup.smtp.starttls=true",
                        "saasforge.iam.password-setup.page-uri=https://console.example.test/password-setup")
                .run(context -> assertThat(context).hasSingleBean(PasswordSetupMailer.class));
    }

    @Test
    void developmentCanStartWithoutSmtpButCannotPretendToDeliver() {
        runner.run(context -> assertThat(context).hasSingleBean(PasswordSetupMailer.class));
    }
}
