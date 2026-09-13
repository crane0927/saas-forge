package io.saasforge.entitlement.application.subscription;

import java.time.Instant;
import java.util.UUID;

/** 原操作者私有记录；UNKNOWN 不允许以新 Key 自动重新创建。 */
public record SubscriptionOperation(UUID id, UUID tenantId, State state, Instant createdAt,
        Instant replayUntil, boolean canReplay, UUID subscriptionId, UUID idempotencyKey) {
    public enum State { COMMITTED, PROCESSING, NOT_COMMITTED, UNKNOWN }
}
