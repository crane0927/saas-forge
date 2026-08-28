package io.saasforge.iam.domain.client;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;

/** 永久保存的非敏感 OAuth Client 管理操作终态。 */
public record OAuthClientManagementOperation(
        UUID id,
        UUID actorIdentityId,
        UUID idempotencyKey,
        String operationType,
        UUID clientId,
        Sha256Digest requestFingerprint,
        String outcome,
        int httpStatus,
        Instant completedAt) {

    public OAuthClientManagementOperation {
        if (id == null || id.version() != 7 || actorIdentityId == null
                || idempotencyKey == null || idempotencyKey.version() != 7
                || !"CREATE".equals(operationType) || clientId == null
                || requestFingerprint == null || !"SUCCEEDED".equals(outcome)
                || httpStatus != 201 || completedAt == null) {
            throw new IllegalArgumentException("OAuth Client 管理操作终态不合法");
        }
    }
}
