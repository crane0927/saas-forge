package io.saasforge.iam.domain.session;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;

/** Family 级 Tenant Context Switch 根工作流；不保存任何 Token、Cookie 或凭据摘要。 */
public record TenantContextSwitchWorkflow(
        UUID id,
        UUID familyId,
        UUID idempotencyKey,
        UUID targetMembershipId,
        Sha256Digest targetFingerprint,
        long expectedContextVersion,
        TenantContextSwitchStatus status,
        Instant createdAt,
        Instant completedAt) {

    public TenantContextSwitchWorkflow {
        if (id == null || familyId == null || idempotencyKey == null || targetMembershipId == null
                || targetFingerprint == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("Tenant Context Switch 工作流必要字段不能为空");
        }
        if (expectedContextVersion < 0) {
            throw new IllegalArgumentException("Tenant Context Switch Context Version 不能为负数");
        }
        if ((status == TenantContextSwitchStatus.PENDING) != (completedAt == null)) {
            throw new IllegalArgumentException("Tenant Context Switch 状态与终结时间不匹配");
        }
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Tenant Context Switch 终结时间不能早于创建时间");
        }
    }

    public boolean sameTarget(Sha256Digest candidateFingerprint) {
        return targetFingerprint.equals(candidateFingerprint);
    }
}
