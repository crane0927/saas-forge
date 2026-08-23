package io.saasforge.iam.domain.identity;

import java.time.Instant;
import java.util.UUID;

/** IAM 对一次 Password Setup 投递请求保存的稳定结果和当前尝试。 */
public record PasswordSetupDelivery(
        UUID callerClientId,
        UUID requestId,
        UUID identityId,
        PasswordSetupDeliveryStatus status,
        UUID challengeId,
        Instant challengeExpiresAt,
        Instant completedAt) {

    public PasswordSetupDelivery {
        if (callerClientId == null || callerClientId.version() != 7
                || requestId == null || requestId.version() != 7
                || identityId == null || identityId.version() != 7 || status == null) {
            throw new IllegalArgumentException("Password Setup 投递事实不合法");
        }
        boolean pending = status == PasswordSetupDeliveryStatus.PENDING;
        boolean delivered = status == PasswordSetupDeliveryStatus.DELIVERED;
        if ((pending || delivered) != (challengeId != null && challengeExpiresAt != null)
                || (status == PasswordSetupDeliveryStatus.PASSWORD_READY
                        && (challengeId != null || challengeExpiresAt != null))
                || (status == PasswordSetupDeliveryStatus.PENDING) != (completedAt == null)) {
            throw new IllegalArgumentException("Password Setup 投递状态不完整");
        }
        if (challengeId != null && challengeId.version() != 7) {
            throw new IllegalArgumentException("Password Setup Challenge ID 必须是 UUIDv7");
        }
    }

    public boolean completed() {
        return status != PasswordSetupDeliveryStatus.PENDING;
    }
}
