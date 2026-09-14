package io.saasforge.entitlement.application.bootstrap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public interface PlanRecoveryRepository {
    record Entry(UUID id, UUID actor, UUID key, PlanOperation.Operation operation, UUID targetId, PlanDraft draft,
            Instant createdAt, Instant replayUntil, PlanResult result) { }
    record Page(List<PlanOperation> items, String nextCursor, boolean hasMore) { }

    /** 独立提交登记，随后主业务回滚或进程重启时仍可由原操作者查找。 */
    Entry prepare(UUID actor, UUID key, PlanOperation.Operation operation, UUID targetId, PlanDraft draft, Instant now);
    /** 事务级互斥覆盖 Plan、幂等结果和恢复结果的同一提交边界。 */
    <T> T locked(UUID actor, UUID id, Function<Entry, T> work);
    void complete(Entry entry, PlanResult result, Instant now);
    PlanOperation get(UUID actor, UUID id, Instant now);
    Page list(UUID actor, String cursor, int limit, Instant now);
}
