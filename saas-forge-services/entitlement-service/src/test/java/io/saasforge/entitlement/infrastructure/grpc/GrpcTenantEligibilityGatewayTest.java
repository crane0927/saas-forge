package io.saasforge.entitlement.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ClientInterceptor;
import io.grpc.Status;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityRequest;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityResponse;
import io.saasforge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saasforge.entitlement.application.subscription.TenantEligibilityUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcTenantEligibilityGatewayTest {
    private TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceBlockingStub client;
    private TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceBlockingStub authenticatedClient;

    @BeforeEach
    void setUp() {
        client = mock(TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceBlockingStub.class);
        authenticatedClient = mock(TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceBlockingStub.class);
        when(client.withInterceptors(any(ClientInterceptor[].class))).thenReturn(authenticatedClient);
    }

    @Test
    void failsClosedWhenTenantAccessReturnsUnknownEligibility() {
        when(authenticatedClient.checkInitialSubscriptionEligibility(
                any(CheckInitialSubscriptionEligibilityRequest.class)))
                .thenReturn(CheckInitialSubscriptionEligibilityResponse.getDefaultInstance());
        GrpcTenantEligibilityGateway gateway = new GrpcTenantEligibilityGateway(client, () -> "token");

        assertThrows(TenantEligibilityUnavailableException.class,
                () -> gateway.checkInitialSubscription(uuidV7(1)));
    }

    @Test
    void failsClosedWhenTenantAccessIsUnavailable() {
        when(authenticatedClient.checkInitialSubscriptionEligibility(
                any(CheckInitialSubscriptionEligibilityRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        GrpcTenantEligibilityGateway gateway = new GrpcTenantEligibilityGateway(client, () -> "token");

        assertThrows(TenantEligibilityUnavailableException.class,
                () -> gateway.checkInitialSubscription(uuidV7(2)));
    }

    @Test
    void failsClosedWhenIamCannotProvideServiceToken() {
        GrpcTenantEligibilityGateway gateway = new GrpcTenantEligibilityGateway(
                client, () -> { throw new IllegalStateException("IAM unavailable"); });

        assertThrows(TenantEligibilityUnavailableException.class,
                () -> gateway.checkInitialSubscription(uuidV7(3)));
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }
}
