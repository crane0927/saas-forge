package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantAdministratorInitializationRow(
        UUID workflowId,
        UUID tenantId,
        UUID actorIdentityId,
        UUID idempotencyKey,
        String requestFingerprint,
        String administratorEmail,
        String administratorDisplayName,
        UUID identityRequestId,
        UUID consumeOperationId,
        UUID releaseOperationId,
        UUID passwordDeliveryRequestId,
        String traceId,
        String outcomeCode,
        Integer responseStatus,
        String responseBody,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        OffsetDateTime expiresAt,
        String workflowState,
        UUID administratorIdentityId,
        String credentialDisposition,
        int attemptCount,
        OffsetDateTime nextAttemptAt,
        String leaseOwner,
        OffsetDateTime leaseUntil,
        OffsetDateTime recoveryExhaustedAt,
        String lastFailure,
        boolean passwordDeliveryPending) {
}
