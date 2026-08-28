package io.saasforge.iam.domain.client;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;

/** 已提交的 Reserved Service Client 替换请求非敏感终态。 */
public record ReservedServiceClientReplacement(
        UUID replacementRequestId,
        ReservedServiceKey serviceKey,
        UUID oldClientId,
        UUID newClientId,
        Sha256Digest requestFingerprint,
        Instant completedAt) {

    public ReservedServiceClientReplacement {
        if (replacementRequestId == null || replacementRequestId.version() != 7
                || serviceKey == null || oldClientId == null || newClientId == null
                || oldClientId.equals(newClientId) || requestFingerprint == null || completedAt == null) {
            throw new IllegalArgumentException("Reserved Service Client Replacement 终态不合法");
        }
    }
}
