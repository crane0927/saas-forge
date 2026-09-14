package io.saasforge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.saasforge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenScopeException;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantProvisioningQueryServerInterceptorTest {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private ServiceAccessTokenAuthorizer tokens;
    private TenantProvisioningQueryServerInterceptor interceptor;
    private ServerCall<String, String> call;
    private ServerCallHandler<String, String> next;
    private ServerCall.Listener<String> listener;
    private Metadata headers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tokens = mock(ServiceAccessTokenAuthorizer.class);
        interceptor = new TenantProvisioningQueryServerInterceptor(tokens);
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);
        listener = mock(ServerCall.Listener.class);
        headers = new Metadata();
        MethodDescriptor<String, String> method = mock(MethodDescriptor.class);
        when(method.getServiceName()).thenReturn(TenantProvisioningQueryServiceGrpc.SERVICE_NAME);
        when(call.getMethodDescriptor()).thenReturn(method);
    }

    @Test
    void acceptsOnlyBearerTokenWithExactTenantReadScope() {
        headers.put(AUTHORIZATION, "Bearer entitlement-token");
        when(next.startCall(call, headers)).thenReturn(listener);

        assertSame(listener, interceptor.interceptCall(call, headers, next));

        verify(tokens).authorize("entitlement-token", "tenant-access:tenant:read");
        verify(call, never()).close(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsWrongScopeWithPermissionDenied() {
        headers.put(AUTHORIZATION, "Bearer wrong-scope-token");
        doThrow(new ServiceAccessTokenScopeException())
                .when(tokens).authorize("wrong-scope-token", "tenant-access:tenant:read");

        interceptor.interceptCall(call, headers, next);

        verify(call).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.PERMISSION_DENIED),
                org.mockito.ArgumentMatchers.any());
        verify(next, never()).startCall(call, headers);
    }

    @Test
    void rejectsInvalidTokenWithUnauthenticated() {
        headers.put(AUTHORIZATION, "Bearer invalid-token");
        doThrow(new ServiceAccessTokenInvalidException())
                .when(tokens).authorize("invalid-token", "tenant-access:tenant:read");

        interceptor.interceptCall(call, headers, next);

        verify(call).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.UNAUTHENTICATED),
                org.mockito.ArgumentMatchers.any());
        verify(next, never()).startCall(call, headers);
    }
}
