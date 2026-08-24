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
        Integer resultHttpStatus,
        Instant createdAt,
        Instant completedAt,
        Instant refreshedAt,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant recoveryExhaustedAt,
        String lastFailure) {

    public TenantContextSwitchWorkflow {
        if (id == null || familyId == null || idempotencyKey == null || targetMembershipId == null
                || targetFingerprint == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("Tenant Context Switch 工作流必要字段不能为空");
        }
        if (expectedContextVersion < 0) {
            throw new IllegalArgumentException("Tenant Context Switch Context Version 不能为负数");
        }
        if (attemptCount < 0 || nextAttemptAt == null || ((leaseOwner == null) != (leaseUntil == null))) {
            throw new IllegalArgumentException("Tenant Context Switch 恢复字段不合法");
        }
        if (lastFailure != null && lastFailure.length() > 100) {
            throw new IllegalArgumentException("Tenant Context Switch 失败摘要过长");
        }
        if ((status == TenantContextSwitchStatus.PENDING) != (completedAt == null)) {
            throw new IllegalArgumentException("Tenant Context Switch 状态与终结时间不匹配");
        }
        boolean successfulSwitch = status == TenantContextSwitchStatus.NO_OP
                || status == TenantContextSwitchStatus.AWAITING_REFRESH
                || status == TenantContextSwitchStatus.POST_SWITCH_REFRESHED
                || status == TenantContextSwitchStatus.POST_SWITCH_REFRESH_REJECTED;
        if (successfulSwitch != Integer.valueOf(204).equals(resultHttpStatus)) {
            throw new IllegalArgumentException("Tenant Context Switch 状态与稳定 HTTP 结果不匹配");
        }
        boolean refreshFinished = status == TenantContextSwitchStatus.POST_SWITCH_REFRESHED
                || status == TenantContextSwitchStatus.POST_SWITCH_REFRESH_REJECTED;
        if (refreshFinished != (refreshedAt != null)) {
            throw new IllegalArgumentException("Tenant Context Switch 状态与 Refresh 完成时间不匹配");
        }
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Tenant Context Switch 终结时间不能早于创建时间");
        }
        if (refreshedAt != null && refreshedAt.isBefore(completedAt)) {
            throw new IllegalArgumentException("Tenant Context Switch Refresh 时间不能早于切换完成时间");
        }
    }

    public boolean sameTarget(Sha256Digest candidateFingerprint) {
        return targetFingerprint.equals(candidateFingerprint);
    }

    public boolean recoveryExhausted() {
        return recoveryExhaustedAt != null;
    }
}
