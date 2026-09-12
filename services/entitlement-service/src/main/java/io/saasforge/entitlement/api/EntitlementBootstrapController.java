package io.saasforge.entitlement.api;

import io.saasforge.entitlement.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionOperation;
import io.saasforge.entitlement.application.bootstrap.PlanOperation;
import io.saasforge.entitlement.application.subscription.SubscriptionOperation;
import io.saasforge.entitlement.contract.model.SubscriptionOperationPage;
import io.saasforge.entitlement.contract.model.SubscriptionOperationRecovery;
import io.saasforge.entitlement.contract.model.PlanOperationRecovery;
import io.saasforge.entitlement.contract.model.PlanOperationPage;
import io.saasforge.entitlement.contract.model.PlanPage;
import io.saasforge.entitlement.contract.model.QuotaDefinitionOperationRecovery;
import io.saasforge.entitlement.contract.model.QuotaDefinitionOperationPage;
import io.saasforge.entitlement.contract.model.QuotaDefinitionPage;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionQueries;
import io.saasforge.entitlement.application.bootstrap.RecoverableQuotaDefinitionService;
import io.saasforge.entitlement.application.bootstrap.PlanResult;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.application.subscription.CreateInitialSubscriptionService;
import io.saasforge.entitlement.application.subscription.InitialSubscriptionResult;
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
    private final io.saasforge.entitlement.application.bootstrap.RecoverablePlanService recoverablePlans;
    private final io.saasforge.entitlement.application.bootstrap.PlanQueries planQueries;
    private final io.saasforge.entitlement.application.subscription.RecoverableSubscriptionService initialSubscriptions;
    private final io.saasforge.entitlement.application.subscription.SubscriptionQueries subscriptionQueries;
    private final RecoverableQuotaDefinitionService recoverableQuota;
    private final QuotaDefinitionQueries quotaQueries;

    public EntitlementBootstrapController(
            PlatformAdminAuthorizer authorizer,
            io.saasforge.entitlement.application.bootstrap.RecoverablePlanService recoverablePlans,
            io.saasforge.entitlement.application.bootstrap.PlanQueries planQueries,
            io.saasforge.entitlement.application.subscription.RecoverableSubscriptionService initialSubscriptions,
            RecoverableQuotaDefinitionService recoverableQuota,
            QuotaDefinitionQueries quotaQueries,
            io.saasforge.entitlement.application.subscription.SubscriptionQueries subscriptionQueries) {
        this.authorizer = authorizer;
        this.recoverablePlans = recoverablePlans;
        this.planQueries = planQueries;
        this.initialSubscriptions = initialSubscriptions;
        this.recoverableQuota = recoverableQuota;
        this.quotaQueries = quotaQueries;
        this.subscriptionQueries = subscriptionQueries;
    }

    /** 仅延后新额度下限校验至幂等判定之后；其余生成约束仍由 MVC 执行。 */
    @org.springframework.web.bind.annotation.InitBinder
    void validatePlanAfterReplayLookup(org.springframework.web.bind.WebDataBinder binder) {
        if (!(binder.getTarget() instanceof CreatePlanRequest)) return;
        var validator = binder.getValidator();
        if (validator == null) return;
        binder.setValidator(new org.springframework.validation.Validator() {
            @Override public boolean supports(Class<?> type) { return validator.supports(type); }
            @Override public void validate(Object target, org.springframework.validation.Errors errors) {
                var checked = new org.springframework.validation.BeanPropertyBindingResult(target, errors.getObjectName());
                validator.validate(target, checked);
                for (var error : checked.getAllErrors()) {
                    if (error instanceof org.springframework.validation.FieldError field) {
                        if ("quotaLimits[0].limit".equals(field.getField()) && "Min".equals(field.getCode())) continue;
                        errors.rejectValue(field.getField(), field.getCode(), field.getArguments(), field.getDefaultMessage());
                    } else errors.reject(error.getCode(), error.getArguments(), error.getDefaultMessage());
                }
            }
        });
    }

    @Override
    public ResponseEntity<QuotaDefinition> createQuotaDefinition(
            UUID idempotencyKey, CreateQuotaDefinitionRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        QuotaDefinitionResult result = recoverableQuota.create(
                actor, idempotencyKey,
                request.getCode() == null ? null : request.getCode().getValue(), traceId(httpRequest));
        return ResponseEntity.created(URI.create("/api/v1/platform/quota-definitions/" + result.id()))
                .body(toResponse(result));
    }

    @Override
    public ResponseEntity<QuotaDefinition> activateQuotaDefinition(
            UUID quotaDefinitionId, UUID idempotencyKey, java.util.Map<String, Object> requestBody) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        if (requestBody != null && !requestBody.isEmpty()) {
            throw new IllegalArgumentException("Quota activation requires an empty JSON object");
        }
        return ResponseEntity.ok(toResponse(recoverableQuota.activate(
                actor, idempotencyKey, quotaDefinitionId, traceId(httpRequest))));
    }

    @Override
    public ResponseEntity<QuotaDefinitionPage> listQuotaDefinitions(
            String cursor, Integer limit, String code, QuotaDefinitionStatus status) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var page = quotaQueries.list(code, status == null ? null
                : io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus.valueOf(status.name()), cursor, limit);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                new QuotaDefinitionPage(
                        page.items().stream().map(EntitlementBootstrapController::toResponse).toList(),
                        page.nextCursor(), page.hasMore()));
    }

    @Override
    public ResponseEntity<QuotaDefinition> getQuotaDefinition(UUID quotaDefinitionId) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(quotaQueries.get(quotaDefinitionId)));
    }

    @Override
    public ResponseEntity<QuotaDefinitionOperationPage> listQuotaDefinitionOperations(
            String cursor, Integer limit) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var page = recoverableQuota.list(actor, cursor, limit);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                new QuotaDefinitionOperationPage(
                        page.items().stream().map(EntitlementBootstrapController::toResponse).toList(),
                        page.nextCursor(), page.hasMore()));
    }

    @Override
    public ResponseEntity<QuotaDefinitionOperationRecovery> getQuotaDefinitionOperation(UUID operationId) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(recoverableQuota.get(actor, operationId)));
    }

    @Override
    public ResponseEntity<QuotaDefinitionOperationRecovery> recoverQuotaDefinitionOperation(
            UUID operationId, UUID idempotencyKey, Object body) {
        var request = currentRequest();
        UUID actor = authorizer.authorize(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!(body instanceof java.util.Map<?, ?> values) || !values.isEmpty()) {
            throw new IllegalArgumentException("Quota recovery requires an empty JSON object");
        }
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(recoverableQuota.recover(actor, operationId, idempotencyKey, traceId(request))));
    }

    private static QuotaDefinitionOperationRecovery toResponse(
            QuotaDefinitionOperation operation) {
        var result = new QuotaDefinitionOperationRecovery(
                operation.id(),
                QuotaDefinitionOperationRecovery.OperationEnum.valueOf(operation.operation().name()),
                QuotaDefinitionOperationRecovery.StateEnum.valueOf(operation.state().name()),
                operation.createdAt().atOffset(ZoneOffset.UTC), operation.replayUntil().atOffset(ZoneOffset.UTC), operation.canReplay());
        result.setQuotaDefinitionId(operation.quotaDefinitionId());
        result.setIdempotencyKey(operation.idempotencyKey());
        return result;
    }

    @Override
    public ResponseEntity<PlanPage> listPlans(
            String cursor, Integer limit, String code, PlanStatus status) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var page = planQueries.list(code, status == null ? null
                : io.saasforge.entitlement.domain.plan.PlanStatus.valueOf(status.name()), cursor, limit);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                new PlanPage(
                        page.items().stream().map(EntitlementBootstrapController::toResponse).toList(),
                        page.nextCursor(), page.hasMore()));
    }

    @Override
    public ResponseEntity<Plan> getPlan(UUID planId) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(planQueries.get(planId)));
    }

    @Override
    public ResponseEntity<SubscriptionOperationPage> listSubscriptionOperations(
            UUID tenantId, String cursor, Integer limit) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var page = initialSubscriptions.list(actor, tenantId, cursor, limit);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                new SubscriptionOperationPage(
                        page.items().stream().map(EntitlementBootstrapController::toResponse).toList(),
                        page.nextCursor(), page.hasMore()));
    }

    @Override
    public ResponseEntity<SubscriptionOperationRecovery> getSubscriptionOperation(UUID operationId) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(initialSubscriptions.get(actor, operationId)));
    }

    @Override
    public ResponseEntity<SubscriptionOperationRecovery> recoverSubscriptionOperation(
            UUID operationId, UUID idempotencyKey, Object body) {
        var request = currentRequest();
        UUID actor = authorizer.authorize(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!(body instanceof java.util.Map<?, ?> values) || !values.isEmpty()) {
            throw new IllegalArgumentException("Subscription recovery requires an empty JSON object");
        }
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(initialSubscriptions.recover(actor, operationId, idempotencyKey, traceId(request))));
    }

    private static SubscriptionOperationRecovery toResponse(
            SubscriptionOperation operation) {
        var result = new SubscriptionOperationRecovery(operation.id(), operation.tenantId(),
                SubscriptionOperationRecovery.StateEnum.valueOf(operation.state().name()),
                operation.createdAt().atOffset(ZoneOffset.UTC), operation.replayUntil().atOffset(ZoneOffset.UTC), operation.canReplay());
        result.setSubscriptionId(operation.subscriptionId());
        result.setIdempotencyKey(operation.idempotencyKey());
        return result;
    }

    @Override
    public ResponseEntity<PlanOperationPage> listPlanOperations(
            String cursor, Integer limit) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var page = recoverablePlans.list(actor, cursor, limit);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
                new PlanOperationPage(
                        page.items().stream().map(EntitlementBootstrapController::toResponse).toList(),
                        page.nextCursor(), page.hasMore()));
    }

    @Override
    public ResponseEntity<PlanOperationRecovery> getPlanOperation(UUID operationId) {
        UUID actor = authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(recoverablePlans.get(actor, operationId)));
    }

    @Override
    public ResponseEntity<PlanOperationRecovery> recoverPlanOperation(
            UUID operationId, UUID idempotencyKey, Object body) {
        var request = currentRequest();
        UUID actor = authorizer.authorize(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!(body instanceof java.util.Map<?, ?> values) || !values.isEmpty()) {
            throw new IllegalArgumentException("Plan recovery requires an empty JSON object");
        }
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(toResponse(recoverablePlans.recover(actor, operationId, idempotencyKey, traceId(request))));
    }

    private static PlanOperationRecovery toResponse(
            PlanOperation operation) {
        var result = new PlanOperationRecovery(
                operation.id(),
                PlanOperationRecovery.OperationEnum.valueOf(operation.operation().name()),
                PlanOperationRecovery.StateEnum.valueOf(operation.state().name()),
                operation.createdAt().atOffset(ZoneOffset.UTC), operation.replayUntil().atOffset(ZoneOffset.UTC), operation.canReplay());
        result.setPlanId(operation.planId());
        result.setCode(operation.code());
        if (operation.result() != null) result.setResult(toResponse(operation.result()));
        result.setIdempotencyKey(operation.idempotencyKey());
        return result;
    }

    @Override
    public ResponseEntity<Plan> createPlan(UUID idempotencyKey, CreatePlanRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        if (request.getQuotaLimits() == null || request.getQuotaLimits().size() != 1 || request.getQuotaLimits().get(0) == null) {
            throw new io.saasforge.entitlement.domain.plan.PlanInvalidException(
                    "Plan 必须恰好包含一个 max_users 限额");
        }
        var quotaLimit = request.getQuotaLimits().get(0);
        PlanResult result = recoverablePlans.create(
                actor, idempotencyKey, new io.saasforge.entitlement.application.bootstrap.PlanDraft(request.getCode(), request.getDisplayName(),
                quotaLimit.getQuotaDefinitionId(), quotaLimit.getLimit()), traceId(httpRequest));
        return ResponseEntity.created(URI.create("/api/v1/platform/plans/" + result.id()))
                .body(toResponse(result));
    }

    @Override
    public ResponseEntity<Plan> activatePlan(UUID planId, UUID idempotencyKey, Object requestBody) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        if (requestBody != null && (!(requestBody instanceof java.util.Map<?, ?> values) || !values.isEmpty())) throw new IllegalArgumentException("Expected empty JSON object");
        return ResponseEntity.ok(toResponse(recoverablePlans.activate(
                actor, idempotencyKey, planId, traceId(httpRequest))));
    }

    @Override
    public ResponseEntity<io.saasforge.entitlement.contract.model.TenantSubscription> getTenantSubscription(UUID tenantId) {
        authorizer.authorize(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var value = subscriptionQueries.get(tenantId);
        var response = new io.saasforge.entitlement.contract.model.TenantSubscription(
                value.observedAt().atOffset(ZoneOffset.UTC),
                value.subscription() == null ? null : toResponse(value.subscription()),
                value.effective(), value.maxUsersLimit(), value.maxUsersUsed());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(response);
    }

    @Override
    public ResponseEntity<Subscription> createInitialSubscription(
            UUID tenantId,
            UUID idempotencyKey,
            io.saasforge.entitlement.contract.model.CreateInitialSubscriptionRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        UUID actor = authorizer.authorize(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        InitialSubscriptionResult result = initialSubscriptions.create(
                actor, idempotencyKey, tenantId, request.getPlanId(),
                request.getEndsAt() == null ? null : request.getEndsAt().toInstant(), traceId(httpRequest));
        return ResponseEntity.created(URI.create(
                        "/api/v1/platform/tenants/" + tenantId + "/subscriptions/" + result.id()))
                .body(toResponse(result));
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

    private static Subscription toResponse(InitialSubscriptionResult result) {
        return new Subscription(
                result.id(), result.tenantId(), result.planId(), Subscription.StatusEnum.ACTIVE,
                result.endsAt() == null ? null : result.endsAt().atOffset(ZoneOffset.UTC),
                result.createdAt().atOffset(ZoneOffset.UTC));
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
