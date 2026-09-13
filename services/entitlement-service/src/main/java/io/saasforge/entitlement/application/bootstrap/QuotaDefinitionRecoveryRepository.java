package io.saasforge.entitlement.application.bootstrap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public interface QuotaDefinitionRecoveryRepository {
    record Entry(UUID id, UUID actor, UUID key, QuotaDefinitionOperation.Operation operation, UUID targetId,
            Instant createdAt, Instant replayUntil, QuotaDefinitionResult result) { }
    record Page(List<QuotaDefinitionOperation> items, String nextCursor, boolean hasMore) { }

    /** 独立提交登记，随后主业务回滚或进程重启时仍可由原操作者查找。 */
    Entry prepare(UUID actor, UUID key, QuotaDefinitionOperation.Operation operation, UUID targetId, Instant now);
    /** 事务级互斥覆盖 Quota Definition、幂等结果和恢复结果的同一提交边界。 */
    <T> T locked(UUID actor, UUID id, Function<Entry, T> work);
    void complete(Entry entry, QuotaDefinitionResult result, Instant now);
    QuotaDefinitionOperation get(UUID actor, UUID id, Instant now);
    Page list(UUID actor, String cursor, int limit, Instant now);
}
