package io.saasforge.entitlement.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.grpc.ManagedChannel;
import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapIdempotency;
import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapService;
import io.saasforge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saasforge.entitlement.application.subscription.CreateInitialSubscriptionService;
import io.saasforge.entitlement.application.subscription.TenantEligibilityGateway;
import io.saasforge.entitlement.domain.outbox.OutboxEventRepository;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionRepository;
import io.saasforge.entitlement.domain.subscription.SubscriptionRepository;
import io.saasforge.entitlement.infrastructure.grpc.GrpcTenantEligibilityGateway;
import io.saasforge.entitlement.infrastructure.security.IamServiceAccessTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class EntitlementBootstrapConfigurationTest {
    @Test
    void wiresBootstrapAndInitialSubscriptionComponents() {
        var configuration = new EntitlementBootstrapConfiguration();
        var clock = configuration.entitlementClock();
        var ids = configuration.entitlementUuidV7Generator(clock);
        EntitlementEventFactory events = configuration.entitlementEventFactory(
                new ObjectMapper(), ids, "topic");

        assertInstanceOf(EntitlementBootstrapService.class, configuration.entitlementBootstrapService(
                Mockito.mock(QuotaDefinitionRepository.class), Mockito.mock(PlanRepository.class),
                Mockito.mock(EntitlementBootstrapIdempotency.class), Mockito.mock(OutboxEventRepository.class),
                events, ids, clock));

        ManagedChannel channel = Mockito.mock(ManagedChannel.class);
        TenantEligibilityGateway eligibility = configuration.tenantEligibilityGateway(
                channel, Mockito.mock(IamServiceAccessTokenProvider.class));
        assertInstanceOf(GrpcTenantEligibilityGateway.class, eligibility);
        assertInstanceOf(CreateInitialSubscriptionService.class,
                configuration.createInitialSubscriptionService(
                        Mockito.mock(PlanRepository.class), Mockito.mock(SubscriptionRepository.class), eligibility,
                        Mockito.mock(EntitlementBootstrapIdempotency.class),
                        Mockito.mock(OutboxEventRepository.class), events, ids, clock));
    }
}
