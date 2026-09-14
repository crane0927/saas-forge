package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdministratorPasswordSetupWorkflowRow(
        UUID workflowId,
        UUID tenantId,
        UUID actorIdentityId,
        UUID idempotencyKey,
        String requestFingerprint,
        UUID administratorIdentityId,
        UUID deliveryRequestId,
        String traceId,
        String outcomeCode,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        OffsetDateTime expiresAt,
        int attemptCount,
        OffsetDateTime nextAttemptAt,
        String leaseOwner,
        OffsetDateTime leaseUntil,
        OffsetDateTime recoveryExhaustedAt,
        String lastFailure) {
}
