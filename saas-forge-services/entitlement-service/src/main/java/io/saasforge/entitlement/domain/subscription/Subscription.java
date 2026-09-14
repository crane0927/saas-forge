package io.saasforge.entitlement.domain.subscription;

import java.time.Instant;
import java.util.UUID;

/** 当前交付切片只表达立即生效且每个 Tenant 仅一次的首 Subscription。 */
public record Subscription(
        UUID id,
        UUID tenantId,
        UUID planId,
        SubscriptionStatus status,
        Instant endsAt,
        Instant createdAt) {
    public Subscription {
        if (id == null || id.version() != 7
                || tenantId == null || tenantId.version() != 7
                || planId == null || planId.version() != 7
                || status != SubscriptionStatus.ACTIVE
                || createdAt == null
                || (endsAt != null && !endsAt.isAfter(createdAt))) {
            throw new IllegalArgumentException("首 Subscription 字段不合法");
        }
    }

    public static Subscription active(
            UUID id, UUID tenantId, UUID planId, Instant endsAt, Instant createdAt) {
        return new Subscription(id, tenantId, planId, SubscriptionStatus.ACTIVE, endsAt, createdAt);
    }
}
