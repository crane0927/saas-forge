package io.saasforge.iam.domain.bootstrap;

import java.time.Instant;
import java.util.UUID;

/** Default Platform Admin 初始凭证重置已经提交的幂等事实。 */
public record PlatformAdminCredentialResetFact(
        UUID resetRequestId,
        UUID identityId,
        UUID credentialId,
        UUID eventId,
        Instant resetAt) {

    public PlatformAdminCredentialResetFact {
        if (resetRequestId == null || resetRequestId.version() != 7
                || identityId == null || credentialId == null
                || eventId == null || eventId.version() != 7 || resetAt == null) {
            throw new IllegalArgumentException("Platform Admin 初始凭证重置事实必要字段不合法");
        }
    }
}
