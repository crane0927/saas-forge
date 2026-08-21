package io.saasforge.iambootstrap;

import io.saasforge.iam.application.authentication.PasswordPolicy;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.application.bootstrap.PlatformAdminCredentialResetEventFactory;
import io.saasforge.iam.application.bootstrap.PlatformAdminCredentialResetService;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformAdminCredentialResetConfiguration {

    @Bean
    Clock credentialResetClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom credentialResetSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    UuidV7Generator credentialResetUuidV7Generator(
            Clock credentialResetClock, SecureRandom credentialResetSecureRandom) {
        return new UuidV7Generator(credentialResetClock, credentialResetSecureRandom);
    }

    @Bean
    PasswordPolicy credentialResetPasswordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    PasswordVerifier credentialResetPasswordVerifier() {
        return new PasswordVerifier();
    }

    @Bean
    PlatformAdminCredentialResetEventFactory platformAdminCredentialResetEventFactory(
            tools.jackson.databind.ObjectMapper objectMapper,
            UuidV7Generator credentialResetUuidV7Generator,
            @Value("${saasforge.environment:dev}") String environment) {
        return new PlatformAdminCredentialResetEventFactory(
                objectMapper, credentialResetUuidV7Generator, environment);
    }

    @Bean
    PlatformAdminCredentialResetService platformAdminCredentialResetService(
            PlatformAdminBootstrapRepository bootstrapFacts,
            PlatformAdminCredentialResetRepository resetFacts,
            IdentityRepository identities,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            OutboxEventRepository outboxEvents,
            PlatformAdminCredentialResetEventFactory eventFactory,
            PasswordPolicy credentialResetPasswordPolicy,
            PasswordVerifier credentialResetPasswordVerifier,
            Clock credentialResetClock) {
        return new PlatformAdminCredentialResetService(
                bootstrapFacts, resetFacts, identities, refreshTokenFamilies, outboxEvents, eventFactory,
                credentialResetPasswordPolicy, credentialResetPasswordVerifier, credentialResetClock);
    }

    @Bean
    SecretTextFileReader credentialResetSecretTextFileReader() {
        return new SecretTextFileReader();
    }

    @Bean
    TraceIdGenerator credentialResetTraceIdGenerator(SecureRandom credentialResetSecureRandom) {
        return new TraceIdGenerator(credentialResetSecureRandom);
    }

    @Bean
    PlatformAdminCredentialResetRunner platformAdminCredentialResetRunner(
            PlatformAdminCredentialResetService resetService,
            SecretTextFileReader credentialResetSecretTextFileReader,
            TraceIdGenerator credentialResetTraceIdGenerator,
            @Value("${saasforge.iam.bootstrap.platform-admin-reset.request-id-file}") Path resetRequestIdFile,
            @Value("${saasforge.iam.bootstrap.platform-admin-reset.password-file}") Path passwordFile) {
        return new PlatformAdminCredentialResetRunner(
                resetService, credentialResetSecretTextFileReader, credentialResetTraceIdGenerator,
                resetRequestIdFile, passwordFile);
    }
}
