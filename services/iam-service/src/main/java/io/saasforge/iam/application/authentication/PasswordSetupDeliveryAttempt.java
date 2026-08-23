package io.saasforge.iam.application.authentication;

import java.time.Instant;
import java.util.UUID;

record PasswordSetupDeliveryAttempt(
        PasswordSetupDeliveryResult completedResult,
        UUID challengeId,
        String recipient,
        String token,
        Instant challengeExpiresAt) {

    static PasswordSetupDeliveryAttempt completed(PasswordSetupDeliveryResult result) {
        return new PasswordSetupDeliveryAttempt(result, null, null, null, null);
    }

    static PasswordSetupDeliveryAttempt pending(
            UUID challengeId, String recipient, String token, Instant challengeExpiresAt) {
        return new PasswordSetupDeliveryAttempt(null, challengeId, recipient, token, challengeExpiresAt);
    }

    boolean completed() {
        return completedResult != null;
    }

    @Override
    public String toString() {
        return completed()
                ? "PasswordSetupDeliveryAttempt[completedResult=" + completedResult + "]"
                : "PasswordSetupDeliveryAttempt[challengeId=" + challengeId
                        + ", recipient=[redacted], token=[redacted], challengeExpiresAt=" + challengeExpiresAt + "]";
    }
}
