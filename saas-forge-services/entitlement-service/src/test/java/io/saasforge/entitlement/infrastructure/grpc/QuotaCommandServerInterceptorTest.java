package io.saasforge.entitlement.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.sdk.auth.ServiceAccessAuthorization;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenScopeException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotaCommandServerInterceptorTest {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final UUID TENANT_ACCESS_CLIENT =
            UUID.fromString("019535d9-0000-7000-8000-000000000001");

    private ServiceAccessTokenAuthorizer tokens;
    private QuotaCommandServerInterceptor interceptor;
    private ServerCall<String, String> call;
    private ServerCallHandler<String, String> next;
    private ServerCall.Listener<String> listener;
    private Metadata headers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tokens = mock(ServiceAccessTokenAuthorizer.class);
        interceptor = new QuotaCommandServerInterceptor(tokens);
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);
        listener = mock(ServerCall.Listener.class);
        headers = new Metadata();
        MethodDescriptor<String, String> method = mock(MethodDescriptor.class);
        when(method.getServiceName()).thenReturn(QuotaCommandServiceGrpc.SERVICE_NAME);
        when(call.getMethodDescriptor()).thenReturn(method);
    }

    @Test
    void acceptsTenantAccessBearerWithExactQuotaWriteScope() {
        headers.put(AUTHORIZATION, "Bearer tenant-access-token");
        when(tokens.authorize("tenant-access-token", "entitlement:quota:write"))
                .thenReturn(new ServiceAccessAuthorization(
                        TENANT_ACCESS_CLIENT, Set.of("entitlement:quota:write")));
        when(next.startCall(call, headers)).thenReturn(listener);

        assertNotNull(interceptor.interceptCall(call, headers, next));
        verify(tokens).authorize("tenant-access-token", "entitlement:quota:write");
        verify(next).startCall(call, headers);
    }

    @Test
    void rejectsRuntimeScopeWithPermissionDenied() {
        headers.put(AUTHORIZATION, "Bearer runtime-token");
        doThrow(new ServiceAccessTokenScopeException())
                .when(tokens).authorize("runtime-token", "entitlement:quota:write");

        interceptor.interceptCall(call, headers, next);

        verify(call).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.PERMISSION_DENIED),
                org.mockito.ArgumentMatchers.any());
        verify(next, never()).startCall(call, headers);
    }

    @Test
    void rejectsInvalidOrMissingToken() {
        interceptor.interceptCall(call, headers, next);
        verify(call).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.UNAUTHENTICATED),
                org.mockito.ArgumentMatchers.any());

        headers.put(AUTHORIZATION, "Bearer invalid-token");
        doThrow(new ServiceAccessTokenInvalidException())
                .when(tokens).authorize("invalid-token", "entitlement:quota:write");
        interceptor.interceptCall(call, headers, next);
        verify(call, org.mockito.Mockito.times(2)).close(org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == Status.Code.UNAUTHENTICATED),
                org.mockito.ArgumentMatchers.any());
    }
}
