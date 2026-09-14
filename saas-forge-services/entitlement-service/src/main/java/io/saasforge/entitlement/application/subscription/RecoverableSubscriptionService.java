package io.saasforge.entitlement.application.subscription;

import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyInvalidException;
import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RecoverableSubscriptionService {
    private final CreateInitialSubscriptionService subscriptions;
    private final SubscriptionRecoveryRepository recovery;
    private final Clock clock;

    public RecoverableSubscriptionService(CreateInitialSubscriptionService subscriptions,
            SubscriptionRecoveryRepository recovery, Clock clock) {
        this.subscriptions = subscriptions;
        this.recovery = recovery;
        this.clock = clock;
    }

    public InitialSubscriptionResult create(UUID actor, UUID key, UUID tenantId, UUID planId, Instant endsAt, String traceId) {
        if (actor == null || actor.version() != 7 || tenantId == null || tenantId.version() != 7
                || planId == null || planId.version() != 7) throw new IllegalArgumentException("Invalid subscription identity");
        if (key == null || key.version() != 7) throw new IdempotencyKeyInvalidException();
        // 新请求的过期时间错误不登记为未决操作；历史稳定结果必须仍可按原 Key 读取。
        if (endsAt != null && !endsAt.isAfter(now()) && recovery.findByKey(actor, key).isEmpty()) {
            throw new IllegalArgumentException("Subscription endsAt must be in the future");
        }
        var saved = recovery.prepare(actor, key, new SubscriptionDraft(tenantId, planId, endsAt), now());
        return recovery.locked(actor, saved.id(), entry -> submit(entry, traceId));
    }

    public SubscriptionOperation get(UUID actor, UUID id) { return recovery.get(actor, id, now()); }
    public SubscriptionRecoveryRepository.Page list(UUID actor, UUID tenantId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size");
        return recovery.list(actor, tenantId, cursor, limit, now());
    }
    public SubscriptionOperation recover(UUID actor, UUID id, UUID key, String traceId) {
        recovery.locked(actor, id, saved -> {
            if (!saved.key().equals(key)) throw new IdempotencyKeyReusedException();
            submit(saved, traceId);
            return null;
        });
        return get(actor, id);
    }
    private InitialSubscriptionResult submit(SubscriptionRecoveryRepository.Entry saved, String traceId) {
        if (!now().isBefore(saved.replayUntil())) throw new SubscriptionRecoveryException("SUBSCRIPTION_RECOVERY_EXPIRED");
        if (saved.result() != null) return saved.result();
        var draft = saved.draft();
        var result = subscriptions.create(saved.actor(), saved.key(), draft.tenantId(), draft.planId(), draft.endsAt(), traceId);
        recovery.complete(saved, result, now());
        return result;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}
