package io.saasforge.entitlement.config;

import io.saasforge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saasforge.entitlement.application.quota.QuotaCommandApplicationService;
import io.saasforge.entitlement.domain.outbox.OutboxEventRepository;
import io.saasforge.entitlement.domain.quota.QuotaOperationRepository;
import io.saasforge.entitlement.infrastructure.security.IamJwksKeyResolver;
import io.saasforge.entitlement.infrastructure.security.RedisServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
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
                clock, issuer, "saasforge-api", Duration.ofSeconds(30));
    }

    @Bean
    ServiceAccessTokenRevocationChecker entitlementServiceAccessTokenRevocationChecker(
            StringRedisTemplate redis,
            @Value("${saasforge.environment:dev}") String environment) {
        return new RedisServiceAccessTokenRevocationChecker(redis, environment);
    }

    @Bean
    ServiceAccessTokenAuthorizer entitlementServiceAccessTokenAuthorizer(
            ServiceAccessTokenSignatureVerifier signatures,
            ServiceAccessTokenRevocationChecker revocations) {
        return new ServiceAccessTokenAuthorizer(signatures, revocations);
    }
}
