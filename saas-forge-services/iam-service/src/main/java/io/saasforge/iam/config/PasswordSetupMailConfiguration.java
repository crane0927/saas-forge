package io.saasforge.iam.config;

import io.saasforge.iam.application.authentication.PasswordSetupDeliveryUnavailableException;
import io.saasforge.iam.application.authentication.PasswordSetupMailer;
import io.saasforge.iam.infrastructure.mail.SmtpPasswordSetupMailer;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration(proxyBeanMethods = false)
public class PasswordSetupMailConfiguration {
    private static final URI DEVELOPMENT_PAGE_URI = URI.create("https://console.saasforge.test/password-setup");

    @Bean
    PasswordSetupMailSettings passwordSetupMailSettings(
            @Value("${saasforge.environment:dev}") String environment,
            @Value("${saasforge.iam.password-setup.smtp.host:}") String host,
            @Value("${saasforge.iam.password-setup.smtp.port:1025}") int port,
            @Value("${saasforge.iam.password-setup.smtp.username:}") String username,
            @Value("${saasforge.iam.password-setup.smtp.password:}") String password,
            @Value("${saasforge.iam.password-setup.smtp.from:}") String from,
            @Value("${saasforge.iam.password-setup.smtp.starttls:false}") boolean startTls,
            @Value("${saasforge.iam.password-setup.smtp.timeout:PT5S}") String timeout,
            @Value("${saasforge.iam.password-setup.page-uri:}") String pageUri) {
        boolean production = "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
        if (production && (host.isBlank() || username.isBlank() || password.isBlank() || from.isBlank()
                || pageUri.isBlank() || !startTls)) {
            throw new IllegalStateException("生产环境必须显式配置 SMTP、STARTTLS、发件人和 Password Setup 页面");
        }
        if (!host.isBlank() && from.isBlank()) {
            throw new IllegalStateException("启用 SMTP 时必须显式配置发件人");
        }
        URI configuredPageUri = pageUri.isBlank() ? DEVELOPMENT_PAGE_URI : URI.create(pageUri);
        return new PasswordSetupMailSettings(
                host, port, username, password, from, startTls, Duration.parse(timeout), configuredPageUri);
    }

    @Bean
    PasswordSetupMailer passwordSetupMailer(PasswordSetupMailSettings settings) {
        if (settings.host().isBlank()) {
            return mail -> { throw new PasswordSetupDeliveryUnavailableException(); };
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        if (!settings.username().isBlank()) {
            sender.setUsername(settings.username());
            sender.setPassword(settings.password());
        }
        int timeoutMillis = Math.toIntExact(settings.timeout().toMillis());
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(timeoutMillis));
        properties.setProperty("mail.smtp.timeout", Integer.toString(timeoutMillis));
        properties.setProperty("mail.smtp.writetimeout", Integer.toString(timeoutMillis));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(settings.startTls()));
        return new SmtpPasswordSetupMailer(sender, settings.from());
    }

    public record PasswordSetupMailSettings(
            String host,
            int port,
            String username,
            String password,
            String from,
            boolean startTls,
            Duration timeout,
            URI pageUri) {
        public PasswordSetupMailSettings {
            if (host == null || port < 1 || port > 65_535 || username == null || password == null
                    || from == null || timeout == null || timeout.isNegative() || timeout.isZero() || pageUri == null) {
                throw new IllegalArgumentException("Password Setup 邮件配置不合法");
            }
        }

        @Override
        public String toString() {
            return "PasswordSetupMailSettings[host=[redacted], port=" + port
                    + ", username=[redacted], password=[redacted], from=[redacted], startTls=" + startTls
                    + ", timeout=" + timeout + ", pageUri=" + pageUri + "]";
        }
    }
}
