package io.saasforge.iam.application.bootstrap;

import java.time.Instant;
import java.util.UUID;

/** 不含密码、Hash 或 Secret 内容的受限重置任务结果。 */
public record PlatformAdminCredentialResetResult(
        Outcome outcome,
        UUID resetRequestId,
        UUID identityId,
        UUID credentialId,
        Instant credentialExpiresAt) {

    public enum Outcome {
        RESET,
        ALREADY_RESET
    }
}
