package io.saasforge.entitlement.api;

import io.saasforge.entitlement.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapService;
import io.saasforge.entitlement.application.bootstrap.PlanResult;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.contract.api.PlatformEntitlementBootstrapApi;
import io.saasforge.entitlement.contract.model.CreatePlanRequest;
import io.saasforge.entitlement.contract.model.CreateQuotaDefinitionRequest;
import io.saasforge.entitlement.contract.model.Plan;
import io.saasforge.entitlement.contract.model.PlanQuotaLimit;
import io.saasforge.entitlement.contract.model.PlanStatus;
import io.saasforge.entitlement.contract.model.QuotaDefinition;
import io.saasforge.entitlement.contract.model.QuotaDefinitionStatus;
import io.saasforge.entitlement.contract.model.Subscription;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
public class EntitlementBootstrapController implements PlatformEntitlementBootstrapApi {
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private final PlatformAdminAuthorizer authorizer;
    private final EntitlementBootstrapService bootstrap;

    public EntitlementBootstrapController(
            PlatformAdminAuthorizer authorizer, EntitlementBootstrapService bootstrap) {
        this.authorizer = authorizer;
        this.bootstrap = bootstrap;
    }

    @Override
    public ResponseEntity<QuotaDefinition> createQuotaDefinition(
            UUID idempotencyKey, CreateQuotaDefinitionRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        QuotaDefinitionResult result = bootstrap.createQuotaDefinition(
                actor, idempotencyKey,
                request.getCode() == null ? null : request.getCode().getValue(), traceId(httpRequest));
        return ResponseEntity.created(URI.create("/api/v1/platform/quota-definitions/" + result.id()))
                .body(toResponse(result));
    }

    @Override
    public ResponseEntity<QuotaDefinition> activateQuotaDefinition(
            UUID quotaDefinitionId, UUID idempotencyKey) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok(toResponse(bootstrap.activateQuotaDefinition(
                actor, idempotencyKey, quotaDefinitionId, traceId(httpRequest))));
    }

    @Override
    public ResponseEntity<Plan> createPlan(UUID idempotencyKey, CreatePlanRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        if (request.getQuotaLimits() == null || request.getQuotaLimits().size() != 1) {
            throw new io.saasforge.entitlement.domain.plan.PlanInvalidException(
                    "Plan 必须恰好包含一个 max_users 限额");
        }
        PlanQuotaLimit quotaLimit = request.getQuotaLimits().get(0);
        PlanResult result = bootstrap.createPlan(
                actor, idempotencyKey, request.getCode(), request.getDisplayName(),
                quotaLimit.getQuotaDefinitionId(), quotaLimit.getLimit(), traceId(httpRequest));
        return ResponseEntity.created(URI.create("/api/v1/platform/plans/" + result.id()))
                .body(toResponse(result));
    }

    @Override
    public ResponseEntity<Plan> activatePlan(UUID planId, UUID idempotencyKey) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok(toResponse(bootstrap.activatePlan(
                actor, idempotencyKey, planId, traceId(httpRequest))));
    }

    @Override
    public ResponseEntity<Subscription> createInitialSubscription(
            UUID tenantId,
            UUID idempotencyKey,
            io.saasforge.entitlement.contract.model.CreateInitialSubscriptionRequest request) {
        return ResponseEntity.status(405).build();
    }

    private static QuotaDefinition toResponse(QuotaDefinitionResult result) {
        return new QuotaDefinition(
                result.id(), QuotaDefinition.CodeEnum.fromValue(result.code()),
                QuotaDefinitionStatus.valueOf(result.status().name()),
                result.createdAt().atOffset(ZoneOffset.UTC), result.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static Plan toResponse(PlanResult result) {
        return new Plan(
                result.id(), result.code(), result.displayName(), PlanStatus.valueOf(result.status().name()),
                result.quotaLimits().stream()
                        .map(limit -> new PlanQuotaLimit(limit.quotaDefinitionId(), limit.limit()))
                        .toList(),
                result.createdAt().atOffset(ZoneOffset.UTC), result.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    static String traceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        Matcher matcher = TRACE_PARENT.matcher(traceparent == null ? "" : traceparent);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
