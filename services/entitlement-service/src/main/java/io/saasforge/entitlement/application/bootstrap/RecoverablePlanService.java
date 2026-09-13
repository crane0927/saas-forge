package io.saasforge.entitlement.application.bootstrap;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RecoverablePlanService {
    private final EntitlementBootstrapService bootstrap;
    private final PlanRecoveryRepository recovery;
    private final Clock clock;

    public RecoverablePlanService(EntitlementBootstrapService bootstrap, PlanRecoveryRepository recovery, Clock clock) {
        this.bootstrap = bootstrap;
        this.recovery = recovery;
        this.clock = clock;
    }

    public PlanResult create(UUID actor, UUID key, PlanDraft draft, String traceId) {
        if (draft == null) throw new IllegalArgumentException("Missing Plan request");
        // 结构和永久非法字段不登记为未决操作；零额度是否为历史重放在稳定结果查找后判断。
        io.saasforge.entitlement.domain.plan.Plan.validateDraftFields(draft.code(), draft.displayName(),
                new io.saasforge.entitlement.domain.plan.PlanQuotaLimit(draft.quotaDefinitionId(),
                        draft.limit() == null ? -1 : draft.limit()));
        return execute(actor, key, PlanOperation.Operation.CREATE, null, draft, traceId);
    }

    public PlanResult activate(UUID actor, UUID key, UUID target, String traceId) {
        if (target == null || target.version() != 7) throw new IllegalArgumentException("Invalid Plan ID");
        return execute(actor, key, PlanOperation.Operation.ACTIVATE, target, null, traceId);
    }

    private PlanResult execute(UUID actor, UUID key, PlanOperation.Operation operation,
            UUID target, PlanDraft draft, String traceId) {
        if (actor == null || actor.version() != 7) throw new IllegalArgumentException("Invalid actor");
        if (key == null || key.version() != 7) throw new IdempotencyKeyInvalidException();
        var saved = recovery.prepare(actor, key, operation, target, draft, now());
        return recovery.locked(actor, saved.id(), entry -> submit(entry, traceId));
    }

    public PlanOperation get(UUID actor, UUID id) { return recovery.get(actor, id, now()); }
    public PlanRecoveryRepository.Page list(UUID actor, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size");
        return recovery.list(actor, cursor, limit, now());
    }
    public PlanOperation recover(UUID actor, UUID id, UUID key, String traceId) {
        recovery.locked(actor, id, saved -> {
            if (!saved.key().equals(key)) throw new IdempotencyKeyReusedException();
            submit(saved, traceId);
            return null;
        });
        return get(actor, id);
    }
    private PlanResult submit(PlanRecoveryRepository.Entry saved, String traceId) {
        if (!now().isBefore(saved.replayUntil())) throw new PlanRecoveryException("PLAN_RECOVERY_EXPIRED");
        if (saved.result() != null) return saved.result();
        // 恢复锁、领域变更、幂等结果和 Outbox 在同一事务提交；失败保留原请求登记。
        var draft = saved.draft();
        var result = saved.operation() == PlanOperation.Operation.CREATE
                ? bootstrap.createPlan(saved.actor(), saved.key(), draft.code(), draft.displayName(),
                        draft.quotaDefinitionId(), draft.limit(), traceId)
                : bootstrap.activatePlan(saved.actor(), saved.key(), saved.targetId(), traceId);
        recovery.complete(saved, result, now());
        return result;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}
