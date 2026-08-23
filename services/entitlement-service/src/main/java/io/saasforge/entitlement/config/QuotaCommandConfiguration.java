package io.saasforge.entitlement.config;

import io.saasforge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saasforge.entitlement.application.quota.QuotaCommandApplicationService;
import io.saasforge.entitlement.domain.outbox.OutboxEventRepository;
import io.saasforge.entitlement.domain.quota.QuotaOperationRepository;
import io.saasforge.entitlement.infrastructure.security.IamJwksKeyResolver;
import io.saasforge.sdk.auth.ServiceAccessTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    ServiceAccessTokenVerifier entitlementServiceAccessTokenVerifier(
            RestClient entitlementIamRestClient,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenVerifier(
                new IamJwksKeyResolver(entitlementIamRestClient),
                clock, issuer, "saasforge-api", Duration.ofSeconds(30));
    }
}
