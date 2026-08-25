package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.UserSessionRevocationBatch;
import io.saasforge.iam.domain.session.UserSessionRevocationRepository;
import io.saasforge.iam.domain.session.UserSessionRevocationStatus;
import io.saasforge.iam.domain.session.UserSessionRevocationWorkflow;
import io.saasforge.iam.domain.session.RevocationFenceTarget;
import java.util.UUID;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

public class UserSessionRevocationTransaction {
    private final UserSessionRevocationRepository workflows;
    private final OutboxEventRepository outbox;
    private final UserSessionsRevokedEventFactory events;
    private final RevocationFenceOperations fences;

    public UserSessionRevocationTransaction(
            UserSessionRevocationRepository workflows,
            OutboxEventRepository outbox,
            UserSessionsRevokedEventFactory events,
            RevocationFenceOperations fences) {
        this.workflows = workflows;
        this.outbox = outbox;
        this.events = events;
        this.fences = fences;
    }

    /** Fence 权威事实与可恢复工作流必须原子建立；Redis 失败会回滚两者。 */
    @Transactional
    public UserSessionRevocationWorkflow prepare(
            UUID requestId, RevocationFenceTarget target, Instant at) {
        fences.establish(requestId, target);
        return workflows.create(requestId, target, at);
    }

    /** Family、jti、游标、累计计数与唯一完成事件必须在同一 PostgreSQL 事务提交。 */
    @Transactional
    public UserSessionRevocationWorkflow commit(
            UserSessionRevocationWorkflow workflow, UserSessionRevocationBatch batch, Instant at) {
        UserSessionRevocationWorkflow updated = workflows.commitBatch(workflow, batch, at);
        if (updated.status() == UserSessionRevocationStatus.COMPLETED) {
            outbox.append(events.create(updated.revocationRequestId(), updated.target(),
                    updated.revokedFamilyCount(), at));
        }
        return updated;
    }
}
