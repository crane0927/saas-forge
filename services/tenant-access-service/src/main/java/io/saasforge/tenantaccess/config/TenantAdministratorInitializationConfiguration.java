package io.saasforge.tenantaccess.config;

import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.contracts.iam.identity.v1.IdentityProvisioningServiceGrpc;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saasforge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saasforge.tenantaccess.application.administrator.InitializationQuotaGateway;
import io.saasforge.tenantaccess.application.administrator.InitializationRecoveryPolicy;
import io.saasforge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saasforge.tenantaccess.application.administrator.PasswordSetupDeliveryGateway;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationRepository;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationWorker;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializedEventFactory;
import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcIdentityProvisioningGateway;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcInitializationQuotaGateway;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcPasswordSetupDeliveryGateway;
import io.saasforge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class TenantAdministratorInitializationConfiguration {
    @Bean
    IdentityProvisioningGateway identityProvisioningGateway(
            GrpcChannelFactory channels, IamServiceAccessTokenProvider tokens) {
        return new GrpcIdentityProvisioningGateway(
                IdentityProvisioningServiceGrpc.newBlockingStub(channels.createChannel("iam")),
                tokens::identityWriteToken);
    }

    @Bean
    InitializationQuotaGateway initializationQuotaGateway(
            GrpcChannelFactory channels, IamServiceAccessTokenProvider tokens) {
        return new GrpcInitializationQuotaGateway(
                QuotaCommandServiceGrpc.newBlockingStub(channels.createChannel("entitlement")),
                tokens::quotaWriteToken);
    }

    @Bean
    PasswordSetupDeliveryGateway passwordSetupDeliveryGateway(
            GrpcChannelFactory channels, IamServiceAccessTokenProvider tokens) {
        return new GrpcPasswordSetupDeliveryGateway(
                PasswordSetupServiceGrpc.newBlockingStub(channels.createChannel("iam")),
                tokens::passwordSetupWriteToken);
    }

    @Bean
    TenantAdministratorInitializedEventFactory tenantAdministratorInitializedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saasforge.tenant-access.outbox-topic}") String topic) {
        return new TenantAdministratorInitializedEventFactory(objectMapper, ids, topic);
    }

    @Bean
    InitializeTenantAdministratorService initializeTenantAdministratorService(
            TenantAdministratorInitializationRepository workflows,
            IdentityProvisioningGateway identities,
            InitializationQuotaGateway quota,
            PasswordSetupDeliveryGateway passwordDeliveries,
            UuidV7Generator ids,
            Clock clock,
            InitializationRecoveryPolicy recoveryPolicy) {
        return new InitializeTenantAdministratorService(
                workflows, identities, quota, passwordDeliveries, ids, clock, recoveryPolicy,
                java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
    }

    @Bean
    InitializationRecoveryPolicy initializationRecoveryPolicy(
            @Value("${saasforge.tenant-access.initialization.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${saasforge.tenant-access.initialization.initial-backoff:PT1S}") Duration initialBackoff,
            @Value("${saasforge.tenant-access.initialization.maximum-backoff:PT1M}") Duration maximumBackoff) {
        return new InitializationRecoveryPolicy(leaseDuration, initialBackoff, maximumBackoff);
    }

    @Bean
    TenantAdministratorInitializationWorker tenantAdministratorInitializationWorker(
            InitializeTenantAdministratorService service) {
        return new TenantAdministratorInitializationWorker(service);
    }
}
