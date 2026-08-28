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
        UUID originalOperationId,
        UUID secretRecordId,
        String outcome,
        int httpStatus,
        Instant completedAt) {

    public OAuthClientManagementOperation {
        boolean supportedTerminal = ("CREATE".equals(operationType) && httpStatus == 201)
                || ("ROTATE".equals(operationType) && httpStatus == 200)
                || ("RECOVER".equals(operationType) && httpStatus == 200)
                || ("REVOKE".equals(operationType) && httpStatus == 204);
        boolean validReferences = switch (operationType == null ? "" : operationType) {
            case "CREATE", "ROTATE" -> originalOperationId == null && secretRecordId != null;
            case "RECOVER" -> originalOperationId != null && secretRecordId != null;
            case "REVOKE" -> originalOperationId == null && secretRecordId == null;
            default -> false;
        };
        if (id == null || id.version() != 7 || actorIdentityId == null
                || idempotencyKey == null || idempotencyKey.version() != 7
                || !supportedTerminal || clientId == null
                || !validReferences
                || requestFingerprint == null || !"SUCCEEDED".equals(outcome)
                || completedAt == null) {
            throw new IllegalArgumentException("OAuth Client 管理操作终态不合法");
        }
    }
}
