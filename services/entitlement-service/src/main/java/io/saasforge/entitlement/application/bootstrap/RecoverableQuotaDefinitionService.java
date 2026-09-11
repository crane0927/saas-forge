package io.saasforge.entitlement.application.bootstrap;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionInvalidException;

@Service
public class RecoverableQuotaDefinitionService {
    private final EntitlementBootstrapService bootstrap;
    private final QuotaDefinitionRecoveryRepository recovery;
    private final Clock clock;
    public RecoverableQuotaDefinitionService(EntitlementBootstrapService bootstrap,
            QuotaDefinitionRecoveryRepository recovery, Clock clock) {
        this.bootstrap = bootstrap; this.recovery = recovery; this.clock = clock;
    }
    public QuotaDefinitionResult create(UUID actor, UUID key, String code, String traceId) {
        if (!"max_users".equals(code)) throw new QuotaDefinitionInvalidException("Only max_users is supported");
        return execute(actor, key, QuotaDefinitionOperation.Operation.CREATE, null, traceId);
    }
    public QuotaDefinitionResult activate(UUID actor, UUID key, UUID target, String traceId) {
        if (target == null || target.version() != 7) throw new IllegalArgumentException("Invalid definition ID");
        return execute(actor, key, QuotaDefinitionOperation.Operation.ACTIVATE, target, traceId);
    }
    private QuotaDefinitionResult execute(UUID actor, UUID key, QuotaDefinitionOperation.Operation operation,
            UUID target, String traceId) {
        if (actor == null || actor.version() != 7) throw new IllegalArgumentException("Invalid actor");
        if (key == null || key.version() != 7) throw new IdempotencyKeyInvalidException();
        var saved = recovery.prepare(actor, key, operation, target, now());
        return recovery.locked(actor, saved.id(), entry -> submit(entry, traceId));
    }
    public QuotaDefinitionOperation get(UUID actor, UUID id) { return recovery.get(actor, id, now()); }
    public QuotaDefinitionRecoveryRepository.Page list(UUID actor, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size");
        return recovery.list(actor, cursor, limit, now());
    }
    public QuotaDefinitionOperation recover(UUID actor, UUID id, UUID key, String traceId) {
        recovery.locked(actor, id, saved -> {
            if (!saved.key().equals(key)) throw new IdempotencyKeyReusedException();
            submit(saved, traceId);
            return null;
        });
        return get(actor, id);
    }
    private QuotaDefinitionResult submit(QuotaDefinitionRecoveryRepository.Entry saved, String traceId) {
        if (!now().isBefore(saved.replayUntil())) {
            throw new QuotaDefinitionRecoveryException("QUOTA_DEFINITION_RECOVERY_EXPIRED");
        }
        if (saved.result() != null) return saved.result();
        // 外层恢复锁、领域变更、幂等结果和 Outbox 共同提交；登记事务单独保留。
        var result = saved.operation() == QuotaDefinitionOperation.Operation.CREATE
                ? bootstrap.createQuotaDefinition(saved.actor(), saved.key(), "max_users", traceId)
                : bootstrap.activateQuotaDefinition(saved.actor(), saved.key(), saved.targetId(), traceId);
        recovery.complete(saved, result, now());
        return result;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}
