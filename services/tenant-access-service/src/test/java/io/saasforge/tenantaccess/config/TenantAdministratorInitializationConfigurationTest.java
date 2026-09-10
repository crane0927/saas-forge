package io.saasforge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.grpc.ManagedChannel;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupRepository;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupWorker;
import io.saasforge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saasforge.tenantaccess.application.administrator.InitializationQuotaGateway;
import io.saasforge.tenantaccess.application.administrator.InitializationRecoveryPolicy;
import io.saasforge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saasforge.tenantaccess.application.administrator.PasswordSetupDeliveryGateway;
import io.saasforge.tenantaccess.application.administrator.ResendAdministratorPasswordSetupService;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationRepository;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationWorker;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializedEventFactory;
import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcIdentityProvisioningGateway;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcInitializationQuotaGateway;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcPasswordSetupDeliveryGateway;
import io.saasforge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
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
