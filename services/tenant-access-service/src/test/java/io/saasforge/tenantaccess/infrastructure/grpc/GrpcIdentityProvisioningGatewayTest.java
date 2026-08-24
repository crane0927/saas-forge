package io.saasforge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ClientInterceptor;
import io.saasforge.contracts.iam.identity.v1.EnsureIdentityResponse;
import io.saasforge.contracts.iam.identity.v1.IdentityCredentialStatus;
import io.saasforge.contracts.iam.identity.v1.IdentityProvisioningServiceGrpc;
import io.saasforge.tenantaccess.application.administrator.IdentityCredentialDisposition;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcIdentityProvisioningGatewayTest {
    private static final UUID REQUEST_ID = UUID.fromString("019535d9-0000-7000-8000-000000000001");
    private static final UUID IDENTITY_ID = UUID.fromString("019535d9-0000-7000-8000-000000000002");
    private IdentityProvisioningServiceGrpc.IdentityProvisioningServiceBlockingStub client;
    private IdentityProvisioningServiceGrpc.IdentityProvisioningServiceBlockingStub authorized;

    @BeforeEach
    void setUp() {
        client = mock(IdentityProvisioningServiceGrpc.IdentityProvisioningServiceBlockingStub.class);
        authorized = mock(IdentityProvisioningServiceGrpc.IdentityProvisioningServiceBlockingStub.class);
        when(client.withInterceptors(any(ClientInterceptor.class))).thenReturn(authorized);
    }

    @Test
    void mapsEverySupportedCredentialDispositionWithAndWithoutDisplayName() {
        assertDisposition(IdentityCredentialStatus.SETUP_ALLOWED, IdentityCredentialDisposition.SETUP_ALLOWED, null);
        assertDisposition(IdentityCredentialStatus.PASSWORD_READY, IdentityCredentialDisposition.PASSWORD_READY, "Admin");
        assertDisposition(IdentityCredentialStatus.RECOVERY_REQUIRED,
                IdentityCredentialDisposition.RECOVERY_REQUIRED, "Admin");
    }

    @Test
    void rejectsUnknownStatusAndUnavailableAccessToken() {
        when(authorized.ensureIdentity(any())).thenReturn(EnsureIdentityResponse.newBuilder()
                .setIdentityId(IDENTITY_ID.toString())
                .setCredentialStatus(IdentityCredentialStatus.IDENTITY_CREDENTIAL_STATUS_UNSPECIFIED)
                .build());
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> gateway(() -> "token").ensure(REQUEST_ID, "admin@example.test", null));
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> gateway(() -> " ").ensure(REQUEST_ID, "admin@example.test", null));
    }

    private void assertDisposition(
            IdentityCredentialStatus status, IdentityCredentialDisposition expected, String displayName) {
        when(authorized.ensureIdentity(any())).thenReturn(EnsureIdentityResponse.newBuilder()
                .setIdentityId(IDENTITY_ID.toString())
                .setCredentialStatus(status)
                .build());
        var result = gateway(() -> "token").ensure(REQUEST_ID, "admin@example.test", displayName);
        assertEquals(IDENTITY_ID, result.identityId());
        assertEquals(expected, result.credentialDisposition());
    }

    private GrpcIdentityProvisioningGateway gateway(java.util.function.Supplier<String> token) {
        return new GrpcIdentityProvisioningGateway(client, token);
    }
}
