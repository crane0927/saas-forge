package io.saasforge.tenantaccess.application.administrator;

import java.time.Instant;
import java.util.UUID;

public record AdministratorPasswordSetupWorkflow(
        UUID workflowId,
        UUID tenantId,
        UUID actorIdentityId,
        UUID idempotencyKey,
        String requestFingerprint,
        UUID administratorIdentityId,
        UUID deliveryRequestId,
        String traceId,
        String outcomeCode,
        Instant createdAt,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant recoveryExhaustedAt,
        String lastFailure) {

    public boolean completed() {
        return outcomeCode != null;
    }
}
