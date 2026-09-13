package io.saasforge.entitlement.application.subscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public interface SubscriptionRecoveryRepository {
    record Entry(UUID id, UUID actor, UUID key, SubscriptionDraft draft,
            Instant createdAt, Instant replayUntil, InitialSubscriptionResult result) { }
    record Page(List<SubscriptionOperation> items, String nextCursor, boolean hasMore) { }
    java.util.Optional<Entry> findByKey(UUID actor, UUID key);
    /** 独立提交登记，后续业务回滚仍保留原请求；不能以新 Key 代替未确认操作。 */
    Entry prepare(UUID actor, UUID key, SubscriptionDraft draft, Instant now);
    /** 锁与 Subscription、幂等响应、Outbox 和恢复结果共享事务。 */
    <T> T locked(UUID actor, UUID id, Function<Entry, T> work);
    void complete(Entry entry, InitialSubscriptionResult result, Instant now);
    SubscriptionOperation get(UUID actor, UUID id, Instant now);
    Page list(UUID actor, UUID tenantId, String cursor, int limit, Instant now);
}
