package io.saas.forge.entitlement.api;

import io.saas.forge.entitlement.application.bootstrap.IdempotencyKeyInvalidException;
import io.saas.forge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import io.saas.forge.entitlement.application.bootstrap.IdempotencyRequestInProgressException;
import io.saas.forge.entitlement.domain.plan.PlanAlreadyExistsException;
import io.saas.forge.entitlement.domain.plan.PlanInvalidException;
import io.saas.forge.entitlement.domain.plan.PlanNotFoundException;
import io.saas.forge.entitlement.domain.plan.PlanNotActiveException;
import io.saas.forge.entitlement.domain.plan.PlanTransitionException;
import io.saas.forge.entitlement.domain.quota.QuotaDefinitionAlreadyExistsException;
import io.saas.forge.entitlement.domain.quota.QuotaDefinitionInvalidException;
import io.saas.forge.entitlement.domain.quota.QuotaDefinitionNotFoundException;
import io.saas.forge.entitlement.domain.quota.QuotaDefinitionTransitionException;
import io.saas.forge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saas.forge.entitlement.application.subscription.TenantEligibilityUnavailableException;
import io.saas.forge.entitlement.application.subscription.TenantExpiryReachedException;
import io.saas.forge.entitlement.application.subscription.TenantInvalidStateException;
import io.saas.forge.entitlement.application.subscription.TenantNotFoundException;
import io.saas.forge.entitlement.domain.subscription.InitialSubscriptionAlreadyExistsException;
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
    @ExceptionHandler(io.saas.forge.entitlement.application.bootstrap.QuotaDefinitionRecoveryException.class)
    ResponseEntity<Problem> recoveryFailure(
            io.saas.forge.entitlement.application.bootstrap.QuotaDefinitionRecoveryException exception,
            HttpServletRequest request) {
        return problem(exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                exception.code(), "Quota Definition recovery unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(io.saas.forge.entitlement.application.subscription.SubscriptionRecoveryException.class)
    ResponseEntity<Problem> recoveryFailure(
            io.saas.forge.entitlement.application.subscription.SubscriptionRecoveryException exception,
            HttpServletRequest request) {
        return problem(exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                exception.code(), "Subscription recovery unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(io.saas.forge.entitlement.application.bootstrap.PlanRecoveryException.class)
    ResponseEntity<Problem> recoveryFailure(
            io.saas.forge.entitlement.application.bootstrap.PlanRecoveryException exception,
            HttpServletRequest request) {
        return problem(exception.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                exception.code(), "Plan recovery unavailable", exception.getMessage(), request);
    }

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
                URI.create("urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId);
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
