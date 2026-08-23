package io.saasforge.entitlement.config;

import io.saasforge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapIdempotency;
import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapService;
import io.saasforge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saasforge.entitlement.application.bootstrap.UuidV7Generator;
import io.saasforge.entitlement.application.subscription.CreateInitialSubscriptionService;
import io.saasforge.entitlement.application.subscription.TenantEligibilityGateway;
import io.saasforge.entitlement.domain.outbox.OutboxEventRepository;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionRepository;
import io.saasforge.entitlement.domain.subscription.SubscriptionRepository;
import io.saasforge.entitlement.infrastructure.grpc.GrpcTenantEligibilityGateway;
import io.saasforge.entitlement.infrastructure.security.IamServiceAccessTokenProvider;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
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
            @Value("${saasforge.entitlement.outbox-topic}") String topic) {
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
            GrpcChannelFactory channels, IamServiceAccessTokenProvider serviceTokens) {
        return new GrpcTenantEligibilityGateway(
                TenantProvisioningQueryServiceGrpc.newBlockingStub(channels.createChannel("tenant-access")),
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
