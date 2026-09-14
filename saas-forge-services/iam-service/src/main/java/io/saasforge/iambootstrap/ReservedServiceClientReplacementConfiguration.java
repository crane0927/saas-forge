package io.saasforge.iambootstrap;

import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientReplacementService;
import io.saasforge.iam.application.client.OAuthClientCreatedEventFactory;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.ReservedServiceClientReplacementRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReservedServiceClientReplacementConfiguration {

    @Bean
    Clock reservedClientReplacementClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom reservedClientReplacementSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    UuidV7Generator reservedClientReplacementUuidV7Generator(
            Clock reservedClientReplacementClock, SecureRandom reservedClientReplacementSecureRandom) {
        return new UuidV7Generator(reservedClientReplacementClock, reservedClientReplacementSecureRandom);
    }

    @Bean
    OAuthClientCreatedEventFactory reservedClientReplacementCreatedEventFactory(
            tools.jackson.databind.ObjectMapper objectMapper,
            UuidV7Generator reservedClientReplacementUuidV7Generator,
            @Value("${saasforge.environment:dev}") String environment) {
        return new OAuthClientCreatedEventFactory(
                objectMapper, reservedClientReplacementUuidV7Generator, environment);
    }

    @Bean
    ReservedServiceClientReplacementService reservedServiceClientReplacementService(
            OAuthClientRepository clients,
            ReservedServiceClientReplacementRepository replacements,
            OutboxEventRepository outbox,
            OAuthClientCreatedEventFactory reservedClientReplacementCreatedEventFactory,
            Clock reservedClientReplacementClock) {
        return new ReservedServiceClientReplacementService(
                clients, replacements, outbox, reservedClientReplacementCreatedEventFactory,
                reservedClientReplacementClock);
    }

    @Bean
    SecretTextFileReader reservedClientReplacementSecretTextFileReader() {
        return new SecretTextFileReader();
    }

    @Bean
    TraceIdGenerator reservedClientReplacementTraceIdGenerator(
            SecureRandom reservedClientReplacementSecureRandom) {
        return new TraceIdGenerator(reservedClientReplacementSecureRandom);
    }

    @Bean
    ReservedServiceClientReplacementRunner reservedServiceClientReplacementRunner(
            ReservedServiceClientReplacementService service,
            SecretTextFileReader reservedClientReplacementSecretTextFileReader,
            TraceIdGenerator reservedClientReplacementTraceIdGenerator,
            @Value("${saasforge.iam.replacement.reserved-client.replacement-request-id}") String requestId,
            @Value("${saasforge.iam.replacement.reserved-client.service-key}") String serviceKey,
            @Value("${saasforge.iam.replacement.reserved-client.old-client-id}") String oldClientId,
            @Value("${saasforge.iam.replacement.reserved-client.new-client-id}") String newClientId,
            @Value("${saasforge.iam.replacement.reserved-client.new-secret-file}") Path newSecretFile) {
        return new ReservedServiceClientReplacementRunner(
                service, reservedClientReplacementSecretTextFileReader,
                reservedClientReplacementTraceIdGenerator, requestId, serviceKey,
                oldClientId, newClientId, newSecretFile);
    }
}
