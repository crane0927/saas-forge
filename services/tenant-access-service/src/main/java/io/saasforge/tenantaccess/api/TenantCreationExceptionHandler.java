package io.saasforge.tenantaccess.api;

import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyInvalidException;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saasforge.tenantaccess.domain.tenant.TenantExpiryInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TenantCreationController.class)
public class TenantCreationExceptionHandler {
    @ExceptionHandler(PlatformAuthorizationDeniedException.class)
    ResponseEntity<Problem> authorizationDenied(
            PlatformAuthorizationDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "PLATFORM_AUTHORIZATION_DENIED",
                "Platform authorization denied", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantExpiryInvalidException.class)
    ResponseEntity<Problem> invalidExpiry(TenantExpiryInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, TenantExpiryInvalidException.CODE,
                "Invalid Tenant expiry", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyInvalidException.class)
    ResponseEntity<Problem> invalidIdempotencyKey(
            IdempotencyKeyInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, IdempotencyKeyInvalidException.CODE,
                "Invalid idempotency key", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<Problem> reusedIdempotencyKey(
            IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, IdempotencyKeyReusedException.CODE,
                "Idempotency key reused", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantAdministratorInitializationException.class)
    ResponseEntity<Problem> initializationFailure(
            TenantAdministratorInitializationException exception, HttpServletRequest request) {
        HttpStatus status = "TENANT_NOT_FOUND".equals(exception.code())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        return problem(status, exception.code(), "Tenant administrator initialization failed",
                exception.getMessage(), request);
    }

    @ExceptionHandler(RemoteWorkflowUnavailableException.class)
    ResponseEntity<Problem> dependencyUnavailable(
            RemoteWorkflowUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_GATEWAY, "TENANT_ADMIN_INITIALIZATION_DEPENDENCY_UNAVAILABLE",
                "Tenant administrator initialization dependency unavailable", exception.getMessage(), request);
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        String traceId = TenantCreationController.traceId(request);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        Problem body = new Problem(
                URI.create("urn:saasforge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
