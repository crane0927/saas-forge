package io.saasforge.entitlement.api;

import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyInvalidException;
import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import io.saasforge.entitlement.application.bootstrap.IdempotencyRequestInProgressException;
import io.saasforge.entitlement.domain.plan.PlanAlreadyExistsException;
import io.saasforge.entitlement.domain.plan.PlanInvalidException;
import io.saasforge.entitlement.domain.plan.PlanNotFoundException;
import io.saasforge.entitlement.domain.plan.PlanNotActiveException;
import io.saasforge.entitlement.domain.plan.PlanTransitionException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionAlreadyExistsException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionInvalidException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionNotFoundException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionTransitionException;
import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saasforge.entitlement.application.subscription.TenantEligibilityUnavailableException;
import io.saasforge.entitlement.application.subscription.TenantExpiryReachedException;
import io.saasforge.entitlement.application.subscription.TenantInvalidStateException;
import io.saasforge.entitlement.application.subscription.TenantNotFoundException;
import io.saasforge.entitlement.domain.subscription.InitialSubscriptionAlreadyExistsException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EntitlementBootstrapController.class)
public class EntitlementBootstrapExceptionHandler {
    @ExceptionHandler(PlatformAuthorizationDeniedException.class)
    ResponseEntity<Problem> authorizationDenied(
            PlatformAuthorizationDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "PLATFORM_AUTHORIZATION_DENIED",
                "Platform authorization denied", exception.getMessage(), request);
    }

    @ExceptionHandler({QuotaDefinitionInvalidException.class, PlanInvalidException.class, IllegalArgumentException.class})
    ResponseEntity<Problem> invalidRequest(RuntimeException exception, HttpServletRequest request) {
        String code = exception instanceof QuotaDefinitionInvalidException
                ? QuotaDefinitionInvalidException.CODE
                : exception instanceof PlanInvalidException ? PlanInvalidException.CODE : "VALIDATION_FAILED";
        return problem(HttpStatus.BAD_REQUEST, code, "Invalid entitlement request", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyInvalidException.class)
    ResponseEntity<Problem> invalidIdempotencyKey(
            IdempotencyKeyInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, IdempotencyKeyInvalidException.CODE,
                "Invalid idempotency key", exception.getMessage(), request);
    }

    @ExceptionHandler({QuotaDefinitionNotFoundException.class, PlanNotFoundException.class, TenantNotFoundException.class})
    ResponseEntity<Problem> notFound(RuntimeException exception, HttpServletRequest request) {
        String code = exception instanceof QuotaDefinitionNotFoundException
                ? QuotaDefinitionNotFoundException.CODE
                : exception instanceof PlanNotFoundException ? PlanNotFoundException.CODE : TenantNotFoundException.CODE;
        return problem(HttpStatus.NOT_FOUND, code, "Entitlement resource not found", exception.getMessage(), request);
    }

    @ExceptionHandler({
            IdempotencyKeyReusedException.class,
            IdempotencyRequestInProgressException.class,
            QuotaDefinitionAlreadyExistsException.class,
            QuotaDefinitionTransitionException.class,
            PlanAlreadyExistsException.class,
            PlanTransitionException.class,
            PlanNotActiveException.class,
            InitialSubscriptionAlreadyExistsException.class,
            TenantInvalidStateException.class,
            TenantExpiryReachedException.class
    })
    ResponseEntity<Problem> conflict(RuntimeException exception, HttpServletRequest request) {
        String code;
        if (exception instanceof IdempotencyKeyReusedException) {
            code = IdempotencyKeyReusedException.CODE;
        } else if (exception instanceof IdempotencyRequestInProgressException) {
            code = IdempotencyRequestInProgressException.CODE;
        } else if (exception instanceof QuotaDefinitionAlreadyExistsException) {
            code = QuotaDefinitionAlreadyExistsException.CODE;
        } else if (exception instanceof QuotaDefinitionTransitionException) {
            code = QuotaDefinitionTransitionException.CODE;
        } else if (exception instanceof PlanAlreadyExistsException) {
            code = PlanAlreadyExistsException.CODE;
        } else if (exception instanceof PlanTransitionException) {
            code = PlanTransitionException.CODE;
        } else if (exception instanceof PlanNotActiveException) {
            code = PlanNotActiveException.CODE;
        } else if (exception instanceof InitialSubscriptionAlreadyExistsException) {
            code = InitialSubscriptionAlreadyExistsException.CODE;
        } else if (exception instanceof TenantInvalidStateException) {
            code = TenantInvalidStateException.CODE;
        } else if (exception instanceof TenantExpiryReachedException) {
            code = TenantExpiryReachedException.CODE;
        } else {
            throw new IllegalStateException("未映射的 Entitlement 冲突", exception);
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (exception instanceof IdempotencyRequestInProgressException) {
            response.header("Retry-After", "1");
        }
        return response.body(body(HttpStatus.CONFLICT, code, "Entitlement conflict", exception.getMessage(), request));
    }

    @ExceptionHandler(TenantEligibilityUnavailableException.class)
    ResponseEntity<Problem> tenantEligibilityUnavailable(
            TenantEligibilityUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_GATEWAY, TenantEligibilityUnavailableException.CODE,
                "Tenant eligibility unavailable", exception.getMessage(), request);
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(status, code, title, detail, request));
    }

    private static Problem body(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        String traceId = EntitlementBootstrapController.traceId(request);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        return new Problem(
                URI.create("urn:saasforge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId);
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
