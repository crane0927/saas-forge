package io.saas.forge.tenantaccess.config;

import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.iam.identity.v1.IdentityProvisioningServiceGrpc;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saas.forge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupRepository;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupWorker;
import io.saas.forge.tenantaccess.application.administrator.InitializationQuotaGateway;
import io.saas.forge.tenantaccess.application.administrator.InitializationRecoveryPolicy;
import io.saas.forge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saas.forge.tenantaccess.application.administrator.PasswordSetupDeliveryGateway;
import io.saas.forge.tenantaccess.application.administrator.ResendAdministratorPasswordSetupService;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationRepository;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationWorker;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializedEventFactory;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcIdentityProvisioningGateway;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcInitializationQuotaGateway;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcPasswordSetupDeliveryGateway;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.grpc.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class TenantAdministratorInitializationConfiguration {
    @Bean
    IdentityProvisioningGateway identityProvisioningGateway(
            @Qualifier("iamServiceChannel") Channel iamChannel, IamServiceAccessTokenProvider tokens) {
        return new GrpcIdentityProvisioningGateway(
                IdentityProvisioningServiceGrpc.newBlockingStub(iamChannel),
                tokens::identityWriteToken);
    }

    @Bean
    InitializationQuotaGateway initializationQuotaGateway(
            @Qualifier("entitlementServiceChannel") Channel entitlementChannel, IamServiceAccessTokenProvider tokens) {
        return new GrpcInitializationQuotaGateway(
                QuotaCommandServiceGrpc.newBlockingStub(entitlementChannel),
                tokens::quotaWriteToken);
    }

    @Bean
    PasswordSetupDeliveryGateway passwordSetupDeliveryGateway(
            @Qualifier("iamServiceChannel") Channel iamChannel, IamServiceAccessTokenProvider tokens) {
        return new GrpcPasswordSetupDeliveryGateway(
                PasswordSetupServiceGrpc.newBlockingStub(iamChannel),
                tokens::passwordSetupWriteToken);
    }

    @Bean
    TenantAdministratorInitializedEventFactory tenantAdministratorInitializedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
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
            @Value("${saas.forge.tenant-access.initialization.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${saas.forge.tenant-access.initialization.initial-backoff:PT1S}") Duration initialBackoff,
            @Value("${saas.forge.tenant-access.initialization.maximum-backoff:PT1M}") Duration maximumBackoff,
            @Value("${saas.forge.tenant-access.initialization.maximum-attempts:10}") int maximumAttempts) {
        return new InitializationRecoveryPolicy(leaseDuration, initialBackoff, maximumBackoff, maximumAttempts);
    }

    @Bean
    TenantAdministratorInitializationWorker tenantAdministratorInitializationWorker(
            InitializeTenantAdministratorService service) {
        return new TenantAdministratorInitializationWorker(service);
    }

    @Bean
    ResendAdministratorPasswordSetupService resendAdministratorPasswordSetupService(
            AdministratorPasswordSetupRepository workflows,
            PasswordSetupDeliveryGateway deliveries,
            UuidV7Generator ids,
            Clock clock,
            InitializationRecoveryPolicy recoveryPolicy) {
        return new ResendAdministratorPasswordSetupService(
                workflows, deliveries, ids, clock, recoveryPolicy,
                java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
    }

    @Bean
    AdministratorPasswordSetupWorker administratorPasswordSetupWorker(
            ResendAdministratorPasswordSetupService service) {
        return new AdministratorPasswordSetupWorker(service);
    }
}
