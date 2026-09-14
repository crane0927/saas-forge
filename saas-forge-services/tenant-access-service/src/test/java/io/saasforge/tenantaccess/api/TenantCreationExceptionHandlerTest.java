package io.saasforge.tenantaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupException;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyInvalidException;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saasforge.tenantaccess.domain.tenant.TenantExpiryInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

class TenantCreationExceptionHandlerTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private final TenantCreationExceptionHandler handler = new TenantCreationExceptionHandler();

    @Test
    void mapsSimpleClientFailuresAndPreservesTraceId() {
        HttpServletRequest request = request("00-" + TRACE_ID + "-0123456789abcdef-01");
        assertResponse(HttpStatus.FORBIDDEN, "PLATFORM_AUTHORIZATION_DENIED",
                handler.authorizationDenied(new PlatformAuthorizationDeniedException(), request), TRACE_ID);
        assertResponse(HttpStatus.BAD_REQUEST, TenantExpiryInvalidException.CODE,
                handler.invalidExpiry(new TenantExpiryInvalidException(), request), TRACE_ID);
        assertResponse(HttpStatus.BAD_REQUEST, IdempotencyKeyInvalidException.CODE,
                handler.invalidIdempotencyKey(new IdempotencyKeyInvalidException(), request), TRACE_ID);
        assertResponse(HttpStatus.CONFLICT, IdempotencyKeyReusedException.CODE,
                handler.reusedIdempotencyKey(new IdempotencyKeyReusedException(), request), TRACE_ID);
    }

    @Test
    void mapsInitializationOutcomesAndRetryHints() {
        HttpServletRequest request = request(null);
        var missing = handler.initializationFailure(
                new TenantAdministratorInitializationException("TENANT_NOT_FOUND", "missing"), request);
        assertResponse(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", missing, null);

        var compensating = handler.initializationFailure(new TenantAdministratorInitializationException(
                "TENANT_ADMIN_INITIALIZATION_COMPENSATING", "retry", 0), request);
        assertResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "TENANT_ADMIN_INITIALIZATION_COMPENSATING", compensating, null);
        assertEquals("1", compensating.getHeaders().getFirst("Retry-After"));

        var conflict = handler.initializationFailure(
                new TenantAdministratorInitializationException("TENANT_ALREADY_INITIALIZED", "done"), request);
        assertResponse(HttpStatus.CONFLICT, "TENANT_ALREADY_INITIALIZED", conflict, null);
    }

    @Test
    void mapsRemoteAndPasswordSetupFailures() {
        HttpServletRequest request = request(null);
        var remote = handler.dependencyUnavailable(
                new RemoteWorkflowUnavailableException(new IllegalStateException("down")), request);
        assertResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "TENANT_ADMIN_INITIALIZATION_DEPENDENCY_UNAVAILABLE", remote, null);
        assertEquals("1", remote.getHeaders().getFirst("Retry-After"));

        var missing = handler.administratorPasswordSetupFailure(
                new AdministratorPasswordSetupException("TENANT_NOT_FOUND", "missing"), request);
        assertResponse(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", missing, null);
        var pending = handler.administratorPasswordSetupFailure(new AdministratorPasswordSetupException(
                "PASSWORD_SETUP_DELIVERY_PENDING", "retry", -1), request);
        assertResponse(HttpStatus.SERVICE_UNAVAILABLE, "PASSWORD_SETUP_DELIVERY_PENDING", pending, null);
        assertEquals("1", pending.getHeaders().getFirst("Retry-After"));
        var conflict = handler.administratorPasswordSetupFailure(new AdministratorPasswordSetupException(
                "IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", "recover"), request);
        assertResponse(HttpStatus.CONFLICT, "IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", conflict, null);
    }

    private static HttpServletRequest request(String traceparent) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getHeader("traceparent")).thenReturn(traceparent);
        return request;
    }

    private static void assertResponse(
            HttpStatus status, String code,
            org.springframework.http.ResponseEntity<TenantCreationExceptionHandler.Problem> response,
            String expectedTraceId) {
        assertEquals(status, response.getStatusCode());
        assertEquals(code, response.getBody().code());
        if (expectedTraceId == null) {
            assertNotNull(response.getBody().traceId());
        } else {
            assertEquals(expectedTraceId, response.getBody().traceId());
        }
        assertFalse(response.getBody().detail().isBlank());
    }
}
