package io.saas.forge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.grpc.ManagedChannel;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupRepository;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupWorker;
import io.saas.forge.tenantaccess.application.administrator.IdentityProvisioningGateway;
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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class TenantAdministratorInitializationConfigurationTest {
    @Test
    void wiresAllInitializationAndRecoveryComponents() {
        var configuration = new TenantAdministratorInitializationConfiguration();
        ManagedChannel channel = Mockito.mock(ManagedChannel.class);
        IamServiceAccessTokenProvider tokens = Mockito.mock(IamServiceAccessTokenProvider.class);

        assertInstanceOf(GrpcIdentityProvisioningGateway.class,
                configuration.identityProvisioningGateway(channel, tokens));
        assertInstanceOf(GrpcInitializationQuotaGateway.class,
                configuration.initializationQuotaGateway(channel, tokens));
        assertInstanceOf(GrpcPasswordSetupDeliveryGateway.class,
                configuration.passwordSetupDeliveryGateway(channel, tokens));

        Clock clock = Clock.systemUTC();
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom());
        assertInstanceOf(TenantAdministratorInitializedEventFactory.class,
                configuration.tenantAdministratorInitializedEventFactory(new ObjectMapper(), ids, "topic"));
        InitializationRecoveryPolicy policy = configuration.initializationRecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 10);

        InitializeTenantAdministratorService initialization = configuration.initializeTenantAdministratorService(
                Mockito.mock(TenantAdministratorInitializationRepository.class),
                Mockito.mock(IdentityProvisioningGateway.class),
                Mockito.mock(InitializationQuotaGateway.class),
                Mockito.mock(PasswordSetupDeliveryGateway.class), ids, clock, policy);
        assertInstanceOf(TenantAdministratorInitializationWorker.class,
                configuration.tenantAdministratorInitializationWorker(initialization));

        ResendAdministratorPasswordSetupService resend = configuration.resendAdministratorPasswordSetupService(
                Mockito.mock(AdministratorPasswordSetupRepository.class),
                Mockito.mock(PasswordSetupDeliveryGateway.class), ids, clock, policy);
        assertInstanceOf(AdministratorPasswordSetupWorker.class,
                configuration.administratorPasswordSetupWorker(resend));
    }
}
