package io.saas.forge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.saas.forge.tenantaccess.application.tenant.SessionRevocationGateway;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleRecoveryPolicy;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleRepository;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleService;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleWorker;
import io.saas.forge.tenantaccess.application.tenant.TenantSuspendedEventFactory;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TenantLifecycleConfigurationTest {
    private final TenantLifecycleConfiguration configuration = new TenantLifecycleConfiguration();

    @Test
    void wiresSessionRevocationLifecycleAndWorkerBoundaries() {
        ManagedChannel channel = mock(ManagedChannel.class);
        IamServiceAccessTokenProvider tokens = mock(IamServiceAccessTokenProvider.class);
        SessionRevocationGateway gateway = configuration.sessionRevocationGateway(channel, tokens);
        UuidV7Generator ids = mock(UuidV7Generator.class);
        TenantSuspendedEventFactory events = configuration.tenantSuspendedEventFactory(
                new ObjectMapper(), ids, "tenant-events");
        TenantLifecycleRecoveryPolicy policy = configuration.tenantLifecycleRecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), 10);
        TenantLifecycleService service = configuration.tenantLifecycleService(
                mock(TenantLifecycleRepository.class), gateway, events, ids, policy, Clock.systemUTC());
        TenantLifecycleService workerService = mock(TenantLifecycleService.class);
        TenantLifecycleWorker worker = configuration.tenantLifecycleWorker(workerService);

        worker.recoverNext();

        assertNotNull(gateway);
        assertNotNull(events);
        assertNotNull(policy);
        assertNotNull(service);
        verify(workerService).recoverNext();
    }

    @Test
    void rejectsInvalidLifecycleRecoveryPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration.tenantLifecycleRecoveryPolicy(
                        Duration.ZERO, Duration.ofSeconds(1), 10));
    }
}
