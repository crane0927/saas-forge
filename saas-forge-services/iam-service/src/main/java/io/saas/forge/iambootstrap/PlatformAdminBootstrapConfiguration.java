package io.saas.forge.iambootstrap;

import io.saas.forge.iam.application.authentication.PasswordPolicy;
import io.saas.forge.iam.application.authentication.PasswordVerifier;
import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.application.bootstrap.PlatformAdminBootstrapService;
import io.saas.forge.iam.application.bootstrap.PlatformAdminInitializedEventFactory;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformAdminBootstrapConfiguration {

    @Bean
    Clock bootstrapClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom bootstrapSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    UuidV7Generator bootstrapUuidV7Generator(Clock bootstrapClock, SecureRandom bootstrapSecureRandom) {
        return new UuidV7Generator(bootstrapClock, bootstrapSecureRandom);
    }

    @Bean
    PasswordPolicy bootstrapPasswordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    PasswordVerifier bootstrapPasswordVerifier() {
        return new PasswordVerifier();
    }

    @Bean
    PlatformAdminInitializedEventFactory platformAdminInitializedEventFactory(
            tools.jackson.databind.ObjectMapper objectMapper,
            UuidV7Generator bootstrapUuidV7Generator,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new PlatformAdminInitializedEventFactory(objectMapper, bootstrapUuidV7Generator, environment);
    }

    @Bean
    PlatformAdminBootstrapService platformAdminBootstrapService(
            IdentityRepository identities,
            PlatformRoleAssignmentRepository platformRoles,
            PlatformAdminBootstrapRepository bootstrapFacts,
            OutboxEventRepository outboxEvents,
            PlatformAdminInitializedEventFactory eventFactory,
            PasswordPolicy bootstrapPasswordPolicy,
            PasswordVerifier bootstrapPasswordVerifier,
            Clock bootstrapClock) {
        return new PlatformAdminBootstrapService(
                identities, platformRoles, bootstrapFacts, outboxEvents, eventFactory,
                bootstrapPasswordPolicy, bootstrapPasswordVerifier, bootstrapClock);
    }

    @Bean
    SecretTextFileReader secretTextFileReader() {
        return new SecretTextFileReader();
    }

    @Bean
    TraceIdGenerator traceIdGenerator(SecureRandom bootstrapSecureRandom) {
        return new TraceIdGenerator(bootstrapSecureRandom);
    }

    @Bean
    PlatformAdminBootstrapRunner platformAdminBootstrapRunner(
            PlatformAdminBootstrapService bootstrapService,
            SecretTextFileReader secretReader,
            TraceIdGenerator traceIdGenerator,
            @Value("${saas.forge.iam.bootstrap.platform-admin.email-file}") Path emailFile,
            @Value("${saas.forge.iam.bootstrap.platform-admin.password-file}") Path passwordFile) {
        return new PlatformAdminBootstrapRunner(
                bootstrapService, secretReader, traceIdGenerator, emailFile, passwordFile);
    }
}
