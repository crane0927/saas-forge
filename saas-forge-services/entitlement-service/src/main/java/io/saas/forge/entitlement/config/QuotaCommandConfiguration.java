package io.saas.forge.entitlement.config;

import io.saas.forge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saas.forge.entitlement.application.quota.QuotaCommandApplicationService;
import io.saas.forge.entitlement.domain.outbox.OutboxEventRepository;
import io.saas.forge.entitlement.domain.quota.QuotaOperationRepository;
import io.saas.forge.entitlement.infrastructure.security.IamJwksKeyResolver;
import io.saas.forge.entitlement.infrastructure.security.RedisServiceAccessTokenRevocationChecker;
import io.saas.forge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saas.forge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class QuotaCommandConfiguration {
    @Bean
    QuotaCommandApplicationService quotaCommandApplicationService(
            QuotaOperationRepository operations,
            OutboxEventRepository outboxEvents,
            EntitlementEventFactory eventFactory,
            Clock clock) {
        return new QuotaCommandApplicationService(operations, outboxEvents, eventFactory, clock);
    }

    @Bean
    ServiceAccessTokenSignatureVerifier entitlementServiceAccessTokenSignatureVerifier(
            RestClient entitlementIamRestClient,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenSignatureVerifier(
                new IamJwksKeyResolver(entitlementIamRestClient),
                clock, issuer, "saas.forge-api", Duration.ofSeconds(30));
    }

    @Bean
    ServiceAccessTokenRevocationChecker entitlementServiceAccessTokenRevocationChecker(
            StringRedisTemplate redis,
            @Value("${saas.forge.environment:dev}") String environment) {
        return new RedisServiceAccessTokenRevocationChecker(redis, environment);
    }

    @Bean
    ServiceAccessTokenAuthorizer entitlementServiceAccessTokenAuthorizer(
            ServiceAccessTokenSignatureVerifier signatures,
            ServiceAccessTokenRevocationChecker revocations) {
        return new ServiceAccessTokenAuthorizer(signatures, revocations);
    }
}
