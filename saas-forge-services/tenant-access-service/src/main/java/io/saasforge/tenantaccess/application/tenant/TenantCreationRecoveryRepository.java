package io.saasforge.tenantaccess.application.tenant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public interface TenantCreationRecoveryRepository {
    record Entry(UUID id, UUID actor, UUID key, String displayName, Instant tenantExpiresAt,
            Instant createdAt, Instant replayUntil, TenantCreationResult result) { }
    record Page(List<TenantCreationRecovery> items, String nextCursor, boolean hasMore) { }

    /** 独立提交登记，随后主业务回滚或进程重启时仍可由原操作者查找。 */
    java.util.Optional<Entry> findByKey(UUID actor, UUID key);
    Entry prepare(UUID actor, UUID key, String name, Instant tenantExpiresAt, Instant now);
    /** 事务级互斥覆盖 Tenant、幂等结果和恢复结果的同一提交边界。 */
    <T> T locked(UUID actor, UUID id, Function<Entry, T> work);
    void complete(Entry entry, TenantCreationResult result, Instant now);
    TenantCreationRecovery get(UUID actor, UUID id, Instant now);
    Page list(UUID actor, String cursor, int limit, Instant now);
}
