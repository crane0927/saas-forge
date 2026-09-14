package io.saasforge.entitlement.application.bootstrap;

import io.saasforge.entitlement.domain.outbox.OutboxEventRepository;
import io.saasforge.entitlement.domain.plan.Plan;
import io.saasforge.entitlement.domain.plan.PlanAlreadyExistsException;
import io.saasforge.entitlement.domain.plan.PlanNotFoundException;
import io.saasforge.entitlement.domain.plan.PlanQuotaLimit;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.domain.plan.PlanTransitionException;
import io.saasforge.entitlement.domain.quota.QuotaDefinition;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionAlreadyExistsException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionInvalidException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionNotFoundException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionRepository;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionTransitionException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class EntitlementBootstrapService {
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final QuotaDefinitionRepository quotaDefinitions;
    private final PlanRepository plans;
    private final EntitlementBootstrapIdempotency idempotency;
    private final OutboxEventRepository outboxEvents;
    private final EntitlementEventFactory eventFactory;
    private final UuidV7Generator ids;
    private final Clock clock;

    public EntitlementBootstrapService(
            QuotaDefinitionRepository quotaDefinitions,
            PlanRepository plans,
            EntitlementBootstrapIdempotency idempotency,
            OutboxEventRepository outboxEvents,
            EntitlementEventFactory eventFactory,
            UuidV7Generator ids,
            Clock clock) {
        this.quotaDefinitions = quotaDefinitions;
        this.plans = plans;
        this.idempotency = idempotency;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.ids = ids;
        this.clock = clock;
    }

    /** 领域状态、稳定响应和对应 Outbox 必须在 Entitlement 本地事务中共同提交。 */
    @Transactional
    public QuotaDefinitionResult createQuotaDefinition(
            UUID actorIdentityId, UUID idempotencyKey, String code, String traceId) {
        Instant now = now();
        UUID definitionId = ids.next();
        String fingerprint = fingerprint("POST", "/api/v1/platform/quota-definitions", code);
        EntitlementBootstrapIdempotency.Entry replay = claimOrReplay(
                actorIdentityId, idempotencyKey,
                EntitlementBootstrapIdempotency.Operation.CREATE_QUOTA_DEFINITION,
                fingerprint, definitionId, now);
        if (replay != null) {
            return requireQuotaResult(replay);
        }

        QuotaDefinition definition = QuotaDefinition.draft(definitionId, code, now);
        if (!quotaDefinitions.create(definition)) {
            throw new QuotaDefinitionAlreadyExistsException();
        }
        QuotaDefinitionResult result = QuotaDefinitionResult.from(definition);
        idempotency.completeQuotaDefinition(actorIdentityId, idempotencyKey, 201, result, now);
        outboxEvents.append(eventFactory.quotaDefinition(result, actorIdentityId, now, traceId, false));
        return result;
    }

    @Transactional
    public QuotaDefinitionResult activateQuotaDefinition(
            UUID actorIdentityId, UUID idempotencyKey, UUID quotaDefinitionId, String traceId) {
        requireUuidV7(quotaDefinitionId, "Quota Definition ID");
        Instant now = now();
        String path = "/api/v1/platform/quota-definitions/" + quotaDefinitionId + "/activations";
        EntitlementBootstrapIdempotency.Entry replay = claimOrReplay(
                actorIdentityId, idempotencyKey,
                EntitlementBootstrapIdempotency.Operation.ACTIVATE_QUOTA_DEFINITION,
                fingerprint("POST", path, ""), quotaDefinitionId, now);
        if (replay != null) {
            return requireQuotaResult(replay);
        }

        QuotaDefinition current = quotaDefinitions.findById(quotaDefinitionId)
                .orElseThrow(QuotaDefinitionNotFoundException::new);
        QuotaDefinition activated = current.activate(now);
        if (!quotaDefinitions.activate(quotaDefinitionId, now)) {
            throw new QuotaDefinitionTransitionException();
        }
        QuotaDefinitionResult result = QuotaDefinitionResult.from(activated);
        idempotency.completeQuotaDefinition(actorIdentityId, idempotencyKey, 200, result, now);
        outboxEvents.append(eventFactory.quotaDefinition(result, actorIdentityId, now, traceId, true));
        return result;
    }

    @Transactional
    public PlanResult createPlan(
            UUID actorIdentityId,
            UUID idempotencyKey,
            String code,
            String displayName,
            UUID quotaDefinitionId,
            Integer limit,
            String traceId) {
        requireUuidV7(quotaDefinitionId, "Quota Definition ID");
        Instant now = now();
        UUID planId = ids.next();
        String body = code + "\n" + displayName + "\n" + quotaDefinitionId + "\n" + limit;
        EntitlementBootstrapIdempotency.Entry replay = claimOrReplay(
                actorIdentityId, idempotencyKey, EntitlementBootstrapIdempotency.Operation.CREATE_PLAN,
                fingerprint("POST", "/api/v1/platform/plans", body), planId, now);
        if (replay != null) {
            return requirePlanResult(replay);
        }

        QuotaDefinition definition = quotaDefinitions.findById(quotaDefinitionId)
                .orElseThrow(QuotaDefinitionNotFoundException::new);
        if (!QuotaDefinition.MAX_USERS.equals(definition.code())
                || definition.status() != QuotaDefinitionStatus.ACTIVE) {
            throw new QuotaDefinitionInvalidException("Plan 必须引用已激活的 max_users Quota Definition");
        }
        Plan plan = Plan.draft(planId, code, displayName,
                new PlanQuotaLimit(quotaDefinitionId, limit == null ? -1 : limit), now);
        if (!plans.create(plan)) {
            throw new PlanAlreadyExistsException();
        }
        PlanResult result = PlanResult.from(plan);
        idempotency.completePlan(actorIdentityId, idempotencyKey, 201, result, now);
        outboxEvents.append(eventFactory.plan(result, actorIdentityId, now, traceId, false));
        return result;
    }

    @Transactional
    public PlanResult activatePlan(
            UUID actorIdentityId, UUID idempotencyKey, UUID planId, String traceId) {
        requireUuidV7(planId, "Plan ID");
        Instant now = now();
        String path = "/api/v1/platform/plans/" + planId + "/activations";
        EntitlementBootstrapIdempotency.Entry replay = claimOrReplay(
                actorIdentityId, idempotencyKey, EntitlementBootstrapIdempotency.Operation.ACTIVATE_PLAN,
                fingerprint("POST", path, ""), planId, now);
        if (replay != null) {
            return requirePlanResult(replay);
        }

        Plan current = plans.findById(planId).orElseThrow(PlanNotFoundException::new);
        Plan activated = current.activate(now);
        QuotaDefinition definition = quotaDefinitions.findById(current.quotaLimits().get(0).quotaDefinitionId())
                .orElseThrow(QuotaDefinitionNotFoundException::new);
        if (!QuotaDefinition.MAX_USERS.equals(definition.code())
                || definition.status() != QuotaDefinitionStatus.ACTIVE
                || current.quotaLimits().size() != 1) {
            throw new QuotaDefinitionInvalidException("Plan 必须恰好包含一个有效 max_users 限额");
        }
        if (!plans.activate(planId, now)) {
            throw new PlanTransitionException();
        }
        PlanResult result = PlanResult.from(activated);
        idempotency.completePlan(actorIdentityId, idempotencyKey, 200, result, now);
        outboxEvents.append(eventFactory.plan(result, actorIdentityId, now, traceId, true));
        return result;
    }

    private EntitlementBootstrapIdempotency.Entry claimOrReplay(
            UUID actorIdentityId,
            UUID idempotencyKey,
            EntitlementBootstrapIdempotency.Operation operation,
            String fingerprint,
            UUID targetId,
            Instant now) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        if (idempotencyKey == null || idempotencyKey.version() != 7) {
            throw new IdempotencyKeyInvalidException();
        }
        idempotency.deleteExpired(actorIdentityId, idempotencyKey, now);
        if (idempotency.claim(actorIdentityId, idempotencyKey, operation, fingerprint,
                targetId, now.plus(IDEMPOTENCY_RETENTION))) {
            return null;
        }
        EntitlementBootstrapIdempotency.Entry existing = idempotency.find(actorIdentityId, idempotencyKey)
                .orElseThrow(IdempotencyRequestInProgressException::new);
        if (existing.operation() != operation || !existing.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        if (!existing.completed()) {
            throw new IdempotencyRequestInProgressException();
        }
        return existing;
    }

    private static QuotaDefinitionResult requireQuotaResult(EntitlementBootstrapIdempotency.Entry entry) {
        if (entry.quotaDefinitionResult() == null) {
            throw new IllegalStateException("Quota Definition 幂等结果损坏");
        }
        return entry.quotaDefinitionResult();
    }

    private static PlanResult requirePlanResult(EntitlementBootstrapIdempotency.Entry entry) {
        if (entry.planResult() == null) {
            throw new IllegalStateException("Plan 幂等结果损坏");
        }
        return entry.planResult();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String fingerprint(String method, String path, String body) {
        try {
            String canonical = method + "\n" + path + "\n" + body;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}
