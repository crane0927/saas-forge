package io.saasforge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ClientInterceptor;
import io.grpc.Status;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.tenantaccess.application.administrator.QuotaUnavailableException;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcInitializationQuotaGatewayTest {
    private static final UUID TENANT_ID = UUID.fromString("019535d9-0000-7000-8000-000000000001");
    private static final UUID OPERATION_ID = UUID.fromString("019535d9-0000-7000-8000-000000000002");

    private QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client;
    private QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub authorized;
    private GrpcInitializationQuotaGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        authorized = mock(QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub.class);
        when(client.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
        gateway = new GrpcInitializationQuotaGateway(client, () -> "service-token");
    }

    @Test
    void sendsConsumeAndReleaseCommandsWithStableQuotaIdentity() {
        gateway.consume(TENANT_ID, OPERATION_ID);
        gateway.release(TENANT_ID, OPERATION_ID);

        var request = QuotaCommandRequest.newBuilder()
                .setTenantId(TENANT_ID.toString())
                .setQuotaCode("max_users")
                .setAmount(1)
                .setOperationId(OPERATION_ID.toString())
                .setPurpose(io.saasforge.contracts.entitlement.quota.v1.QuotaPurpose.TENANT_ADMIN_INITIALIZATION)
                .build();
        verify(authorized).consume(request);
        verify(authorized).release(request);
    }

    @Test
    void mapsQuotaExhaustionAndMissingSubscriptionToQuotaUnavailable() {
        doThrow(Status.RESOURCE_EXHAUSTED.withDescription("QUOTA_EXCEEDED").asRuntimeException())
                .when(authorized).consume(any());

        QuotaUnavailableException exceeded = assertThrows(
                QuotaUnavailableException.class, () -> gateway.consume(TENANT_ID, OPERATION_ID));
        assertEquals("QUOTA_EXCEEDED", exceeded.getMessage());

        doThrow(Status.FAILED_PRECONDITION.withDescription("SUBSCRIPTION_REQUIRED").asRuntimeException())
                .when(authorized).consume(any());
        QuotaUnavailableException missing = assertThrows(
                QuotaUnavailableException.class, () -> gateway.consume(TENANT_ID, OPERATION_ID));
        assertEquals("SUBSCRIPTION_REQUIRED", missing.getMessage());
    }

    @Test
    void mapsEveryOtherGrpcOrLocalFailureToRemoteUnavailable() {
        doThrow(Status.RESOURCE_EXHAUSTED.withDescription("OTHER").asRuntimeException())
                .when(authorized).consume(any());
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> gateway.consume(TENANT_ID, OPERATION_ID));

        doThrow(Status.RESOURCE_EXHAUSTED.withDescription("QUOTA_EXCEEDED").asRuntimeException())
                .when(authorized).release(any());
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> gateway.release(TENANT_ID, OPERATION_ID));

        GrpcInitializationQuotaGateway localFailure = new GrpcInitializationQuotaGateway(
                client, () -> { throw new IllegalStateException("token unavailable"); });
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> localFailure.consume(TENANT_ID, OPERATION_ID));
    }
}
