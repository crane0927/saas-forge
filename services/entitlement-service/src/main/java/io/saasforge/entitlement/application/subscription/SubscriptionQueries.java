package io.saasforge.entitlement.application.subscription;

import java.time.Instant;
import java.util.UUID;

public interface SubscriptionQueries {
    /** 只表达 Entitlement 权威事实；不存在订阅时额度为 null，不判定 Tenant 生命周期。 */
    record Result(Instant observedAt, InitialSubscriptionResult subscription, boolean effective,
            Integer maxUsersLimit, Integer maxUsersUsed) { }
    Result get(UUID tenantId);
}
