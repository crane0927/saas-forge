package io.saas.forge.iam.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saas.forge.iam.application.authentication.PasswordSetupMailer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PasswordSetupMailConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PasswordSetupMailConfiguration.class);

    @Test
    void productionRequiresExplicitAuthenticatedStartTlsSmtpAndPageUri() {
        runner.withPropertyValues("saas.forge.environment=prod")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues(
                        "saas.forge.environment=prod",
                        "saas.forge.iam.password-setup.smtp.host=smtp.example.test",
                        "saas.forge.iam.password-setup.smtp.port=587",
                        "saas.forge.iam.password-setup.smtp.username=mailer",
                        "saas.forge.iam.password-setup.smtp.password=secret-value",
                        "saas.forge.iam.password-setup.smtp.from=no-reply@example.test",
                        "saas.forge.iam.password-setup.smtp.starttls=true",
                        "saas.forge.iam.password-setup.page-uri=https://console.example.test/password-setup")
                .run(context -> assertThat(context).hasSingleBean(PasswordSetupMailer.class));
    }

    @Test
    void developmentCanStartWithoutSmtpButCannotPretendToDeliver() {
        runner.run(context -> assertThat(context).hasSingleBean(PasswordSetupMailer.class));
    }
}
