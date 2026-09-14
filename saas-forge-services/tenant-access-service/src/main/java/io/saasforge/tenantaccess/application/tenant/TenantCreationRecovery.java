package io.saasforge.tenantaccess.application.tenant;

import java.time.Instant;
import java.util.UUID;

/** 原操作者私有的恢复读取；NOT_COMMITTED 只证明读取时未提交，不允许换 Key 自动新建。 */
public record TenantCreationRecovery(UUID id, String displayName, State state, Instant createdAt,
        Instant replayUntil, boolean canReplay, UUID tenantId, UUID idempotencyKey) {
    public enum State { COMMITTED, PROCESSING, NOT_COMMITTED, UNKNOWN }
}
