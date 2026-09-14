package io.saasforge.iam.application.bootstrap;

import java.time.Instant;
import java.util.UUID;

/** 不含邮箱、密码或 Hash 的部署任务结果。 */
public record PlatformAdminBootstrapResult(
        Outcome outcome,
        UUID identityId,
        UUID credentialId,
        UUID roleAssignmentId,
        Instant credentialExpiresAt) {

    public enum Outcome {
        INITIALIZED,
        ALREADY_INITIALIZED
    }
}
