package io.saasforge.iam.domain.session;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;

public interface TenantContextSwitchRepository {

    /** 在任何 Tenant Access 调用前锁定 Family 并创建或恢复唯一根工作流。 */
    TenantContextSwitchClaim claim(
            UUID familyId,
            long expectedContextVersion,
            UUID idempotencyKey,
            UUID targetMembershipId,
            Sha256Digest targetFingerprint,
            Instant createdAt);

    void complete(UUID workflowId, TenantContextSwitchStatus status, Instant completedAt);
}
