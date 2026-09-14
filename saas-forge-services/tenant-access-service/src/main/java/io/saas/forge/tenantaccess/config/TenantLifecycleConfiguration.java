package io.saas.forge.tenantaccess.config;

import io.saas.forge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationGateway;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleRecoveryPolicy;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleRepository;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleService;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleWorker;
import io.saas.forge.tenantaccess.application.tenant.TenantSuspendedEventFactory;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcSessionRevocationGateway;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.grpc.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class TenantLifecycleConfiguration {
    @Bean
    SessionRevocationGateway sessionRevocationGateway(
            @Qualifier("iamServiceChannel") Channel iamChannel, IamServiceAccessTokenProvider tokens) {
        return new GrpcSessionRevocationGateway(
                UserSessionRevocationServiceGrpc.newBlockingStub(iamChannel),
                tokens::sessionWriteToken);
    }

    @Bean
    TenantSuspendedEventFactory tenantSuspendedEventFactory(
            ObjectMapper objectMapper, UuidV7Generator ids,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new TenantSuspendedEventFactory(objectMapper, ids, topic);
    }

    @Bean
    TenantLifecycleRecoveryPolicy tenantLifecycleRecoveryPolicy(
            @Value("${saas.forge.tenant-access.lifecycle.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${saas.forge.tenant-access.lifecycle.retry-delay:PT1S}") Duration retryDelay,
            @Value("${saas.forge.tenant-access.lifecycle.maximum-attempts:10}") int maximumAttempts) {
        return new TenantLifecycleRecoveryPolicy(leaseDuration, retryDelay, maximumAttempts);
    }

    @Bean
    TenantLifecycleService tenantLifecycleService(
            TenantLifecycleRepository workflows, SessionRevocationGateway revocations,
            TenantSuspendedEventFactory events, UuidV7Generator ids,
            TenantLifecycleRecoveryPolicy policy, Clock clock) {
        return new TenantLifecycleService(workflows, revocations, events, ids, policy, clock,
                ManagementFactory.getRuntimeMXBean().getName());
    }

    @Bean
    TenantLifecycleWorker tenantLifecycleWorker(TenantLifecycleService service) {
        return new TenantLifecycleWorker(service);
    }
}
