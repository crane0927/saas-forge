package io.saasforge.iam.application.authentication;

import java.time.Instant;
import java.util.UUID;

/** 只向受信调用方返回一次的 Password Setup Challenge 明文。 */
public record PasswordSetupChallengeToken(UUID challengeId, String value, Instant expiresAt) {
    public PasswordSetupChallengeToken {
        if (challengeId == null || challengeId.version() != 7
                || value == null || value.length() != 43 || expiresAt == null) {
            throw new IllegalArgumentException("Password Setup Challenge Token 不合法");
        }
    }

    @Override
    public String toString() {
        return "PasswordSetupChallengeToken[challengeId=" + challengeId
                + ", value=[redacted], expiresAt=" + expiresAt + "]";
    }
}
