package io.saasforge.entitlement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyInvalidException;
import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import io.saasforge.entitlement.application.bootstrap.IdempotencyRequestInProgressException;
import io.saasforge.entitlement.application.subscription.TenantEligibilityUnavailableException;
import io.saasforge.entitlement.application.subscription.TenantExpiryReachedException;
import io.saasforge.entitlement.application.subscription.TenantInvalidStateException;
import io.saasforge.entitlement.application.subscription.TenantNotFoundException;
import io.saasforge.entitlement.domain.plan.PlanAlreadyExistsException;
import io.saasforge.entitlement.domain.plan.PlanInvalidException;
import io.saasforge.entitlement.domain.plan.PlanNotActiveException;
import io.saasforge.entitlement.domain.plan.PlanNotFoundException;
import io.saasforge.entitlement.domain.plan.PlanTransitionException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionAlreadyExistsException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionInvalidException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionNotFoundException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionTransitionException;
import io.saasforge.entitlement.domain.subscription.InitialSubscriptionAlreadyExistsException;
import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class EntitlementBootstrapExceptionHandlerTest {
    private static final String TRACEPARENT =
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
    private final EntitlementBootstrapExceptionHandler handler = new EntitlementBootstrapExceptionHandler();

    @Test
    void mapsAuthorizationIdempotencyAndUnavailableFailures() {
        MockHttpServletRequest request = requestWithTrace();

        assertProblem(handler.authorizationDenied(new PlatformAuthorizationDeniedException(), request),
                HttpStatus.FORBIDDEN, "PLATFORM_AUTHORIZATION_DENIED");
        assertProblem(handler.invalidIdempotencyKey(new IdempotencyKeyInvalidException(), request),
                HttpStatus.BAD_REQUEST, IdempotencyKeyInvalidException.CODE);
        assertProblem(handler.tenantEligibilityUnavailable(
                        new TenantEligibilityUnavailableException(new IllegalStateException()), request),
                HttpStatus.BAD_GATEWAY, TenantEligibilityUnavailableException.CODE);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void mapsInvalidRequests(RuntimeException exception, String code) {
        assertProblem(handler.invalidRequest(exception, requestWithTrace()), HttpStatus.BAD_REQUEST, code);
    }

    @ParameterizedTest
    @MethodSource("missingResources")
    void mapsMissingResources(RuntimeException exception, String code) {
        assertProblem(handler.notFound(exception, requestWithTrace()), HttpStatus.NOT_FOUND, code);
    }

    @ParameterizedTest
    @MethodSource("conflicts")
    void mapsEveryConflict(RuntimeException exception, String code) {
        var response = handler.conflict(exception, requestWithTrace());

        assertProblem(response, HttpStatus.CONFLICT, code);
        assertEquals(exception instanceof IdempotencyRequestInProgressException ? "1" : null,
                response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void rejectsAnUnmappedConflictAndCreatesFallbackTraceId() {
        assertThrows(IllegalStateException.class,
                () -> handler.conflict(new RuntimeException("unmapped"), requestWithTrace()));

        var response = handler.invalidRequest(new IllegalArgumentException("invalid"), new MockHttpServletRequest());
        assertNotNull(response.getBody());
        assertEquals(32, response.getBody().traceId().length());
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(new QuotaDefinitionInvalidException("invalid"),
                        QuotaDefinitionInvalidException.CODE),
                Arguments.of(new PlanInvalidException("invalid"), PlanInvalidException.CODE),
                Arguments.of(new IllegalArgumentException("invalid"), "VALIDATION_FAILED"));
    }

    private static Stream<Arguments> missingResources() {
        return Stream.of(
                Arguments.of(new QuotaDefinitionNotFoundException(), QuotaDefinitionNotFoundException.CODE),
                Arguments.of(new PlanNotFoundException(), PlanNotFoundException.CODE),
                Arguments.of(new TenantNotFoundException(), TenantNotFoundException.CODE));
    }

    private static Stream<Arguments> conflicts() {
        return Stream.of(
                Arguments.of(new IdempotencyKeyReusedException(), IdempotencyKeyReusedException.CODE),
                Arguments.of(new IdempotencyRequestInProgressException(), IdempotencyRequestInProgressException.CODE),
                Arguments.of(new QuotaDefinitionAlreadyExistsException(), QuotaDefinitionAlreadyExistsException.CODE),
                Arguments.of(new QuotaDefinitionTransitionException(), QuotaDefinitionTransitionException.CODE),
                Arguments.of(new PlanAlreadyExistsException(), PlanAlreadyExistsException.CODE),
                Arguments.of(new PlanTransitionException(), PlanTransitionException.CODE),
                Arguments.of(new PlanNotActiveException(), PlanNotActiveException.CODE),
                Arguments.of(new InitialSubscriptionAlreadyExistsException(),
                        InitialSubscriptionAlreadyExistsException.CODE),
                Arguments.of(new TenantInvalidStateException(), TenantInvalidStateException.CODE),
                Arguments.of(new TenantExpiryReachedException(), TenantExpiryReachedException.CODE));
    }

    private static MockHttpServletRequest requestWithTrace() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", TRACEPARENT);
        return request;
    }

    private static void assertProblem(
            org.springframework.http.ResponseEntity<EntitlementBootstrapExceptionHandler.Problem> response,
            HttpStatus status,
            String code) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(code, response.getBody().code());
        assertEquals("1234567890abcdef1234567890abcdef", response.getBody().traceId());
    }
}
