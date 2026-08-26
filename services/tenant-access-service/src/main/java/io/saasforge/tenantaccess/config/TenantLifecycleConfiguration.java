package io.saasforge.tenantaccess.config;

import io.saasforge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saasforge.tenantaccess.application.tenant.SessionRevocationGateway;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleRecoveryPolicy;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleRepository;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleService;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleWorker;
import io.saasforge.tenantaccess.application.tenant.TenantSuspendedEventFactory;
import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcSessionRevocationGateway;
import io.saasforge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class TenantLifecycleConfiguration {
    @Bean
    SessionRevocationGateway sessionRevocationGateway(
            GrpcChannelFactory channels, IamServiceAccessTokenProvider tokens) {
        return new GrpcSessionRevocationGateway(
                UserSessionRevocationServiceGrpc.newBlockingStub(channels.createChannel("iam")),
                tokens::sessionWriteToken);
    }

    @Bean
    TenantSuspendedEventFactory tenantSuspendedEventFactory(
            ObjectMapper objectMapper, UuidV7Generator ids,
            @Value("${saasforge.tenant-access.outbox-topic}") String topic) {
        return new TenantSuspendedEventFactory(objectMapper, ids, topic);
    }

    @Bean
    TenantLifecycleRecoveryPolicy tenantLifecycleRecoveryPolicy(
            @Value("${saasforge.tenant-access.lifecycle.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${saasforge.tenant-access.lifecycle.retry-delay:PT1S}") Duration retryDelay,
            @Value("${saasforge.tenant-access.lifecycle.maximum-attempts:10}") int maximumAttempts) {
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
