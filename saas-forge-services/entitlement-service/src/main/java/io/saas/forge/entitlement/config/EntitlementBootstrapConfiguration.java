package io.saas.forge.entitlement.config;

import io.saas.forge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saas.forge.entitlement.application.bootstrap.EntitlementBootstrapIdempotency;
import io.saas.forge.entitlement.application.bootstrap.EntitlementBootstrapService;
import io.saas.forge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saas.forge.entitlement.application.bootstrap.UuidV7Generator;
import io.saas.forge.entitlement.application.subscription.CreateInitialSubscriptionService;
import io.saas.forge.entitlement.application.subscription.TenantEligibilityGateway;
import io.saas.forge.entitlement.domain.outbox.OutboxEventRepository;
import io.saas.forge.entitlement.domain.plan.PlanRepository;
import io.saas.forge.entitlement.domain.quota.QuotaDefinitionRepository;
import io.saas.forge.entitlement.domain.subscription.SubscriptionRepository;
import io.saas.forge.entitlement.infrastructure.grpc.GrpcTenantEligibilityGateway;
import io.saas.forge.entitlement.infrastructure.security.IamServiceAccessTokenProvider;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.grpc.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class EntitlementBootstrapConfiguration {
    @Bean
    Clock entitlementClock() {
        return Clock.systemUTC();
    }

    @Bean
    UuidV7Generator entitlementUuidV7Generator(Clock clock) {
        return new UuidV7Generator(clock, new SecureRandom());
    }

    @Bean
    EntitlementEventFactory entitlementEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saas.forge.entitlement.outbox-topic}") String topic) {
        return new EntitlementEventFactory(objectMapper, ids, topic);
    }

    @Bean
    EntitlementBootstrapService entitlementBootstrapService(
            QuotaDefinitionRepository quotaDefinitions,
            PlanRepository plans,
            EntitlementBootstrapIdempotency idempotency,
            OutboxEventRepository outboxEvents,
            EntitlementEventFactory eventFactory,
            UuidV7Generator ids,
            Clock clock) {
        return new EntitlementBootstrapService(
                quotaDefinitions, plans, idempotency, outboxEvents, eventFactory, ids, clock);
    }

    @Bean
    TenantEligibilityGateway tenantEligibilityGateway(
            @Qualifier("tenantAccessServiceChannel") Channel tenantAccessChannel, IamServiceAccessTokenProvider serviceTokens) {
        return new GrpcTenantEligibilityGateway(
                TenantProvisioningQueryServiceGrpc.newBlockingStub(tenantAccessChannel),
                serviceTokens::tenantReadToken);
    }

    @Bean
    CreateInitialSubscriptionService createInitialSubscriptionService(
            PlanRepository plans,
            SubscriptionRepository subscriptions,
            TenantEligibilityGateway tenantEligibility,
            EntitlementBootstrapIdempotency idempotency,
            OutboxEventRepository outboxEvents,
            EntitlementEventFactory eventFactory,
            UuidV7Generator ids,
            Clock clock) {
        return new CreateInitialSubscriptionService(
                plans, subscriptions, tenantEligibility, idempotency, outboxEvents, eventFactory, ids, clock);
    }
}
