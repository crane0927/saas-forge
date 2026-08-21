package io.saasforge.iam.domain.bootstrap;

import java.time.Instant;
import java.util.UUID;

/** Default Platform Admin 首次引导已经提交的幂等事实。 */
public record PlatformAdminBootstrapFact(
        UUID identityId,
        UUID credentialId,
        UUID roleAssignmentId,
        UUID eventId,
        Instant initializedAt) {

    public PlatformAdminBootstrapFact {
        if (identityId == null || credentialId == null || roleAssignmentId == null
                || eventId == null || eventId.version() != 7 || initializedAt == null) {
            throw new IllegalArgumentException("Platform Admin 引导事实必要字段不合法");
        }
    }
}
