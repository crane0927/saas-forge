package io.saasforge.iam.domain.session;

import java.time.Instant;
import java.util.UUID;

/** 目标会话撤销的耐久进度；fencingToken 标识当前数据库租约代际。 */
public record UserSessionRevocationWorkflow(
        UUID revocationRequestId,
        RevocationFenceTarget target,
        UserSessionRevocationStatus status,
        UUID cursorFamilyId,
        long revokedFamilyCount,
        long revokedJtiCount,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        long fencingToken,
        Instant recoveryExhaustedAt,
        String lastFailure,
        Instant completedAt) {

    public UserSessionRevocationWorkflow {
        if (revocationRequestId == null || target == null || status == null || nextAttemptAt == null
                || revokedFamilyCount < 0 || revokedJtiCount < 0 || attemptCount < 0 || fencingToken < 0
                || ((leaseOwner == null) != (leaseUntil == null))) {
            throw new IllegalArgumentException("User Session Revocation 工作流字段不合法");
        }
        if ((status == UserSessionRevocationStatus.COMPLETED) != (completedAt != null)
                || (status == UserSessionRevocationStatus.RECOVERY_REQUIRED) != (recoveryExhaustedAt != null)
                || (lastFailure != null && lastFailure.length() > 100)) {
            throw new IllegalArgumentException("User Session Revocation 工作流状态不一致");
        }
    }

    public boolean leased() {
        return leaseOwner != null;
    }
}
