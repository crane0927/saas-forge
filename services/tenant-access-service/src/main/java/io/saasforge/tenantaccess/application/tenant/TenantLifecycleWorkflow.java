package io.saasforge.tenantaccess.application.tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantLifecycleWorkflow(
        UUID workflowId,
        UUID tenantId,
        UUID actorIdentityId,
        UUID idempotencyKey,
        String requestFingerprint,
        TenantLifecycleAction action,
        UUID revocationRequestId,
        UUID releaseRequestId,
        TenantLifecycleStatus status,
        boolean fenceEstablished,
        long revokedFamilyCount,
        long revokedJtiCount,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        long fencingToken,
        Instant recoveryStartedAt,
        Instant iamRecoveryConfirmedAt,
        Instant completedAt,
        TenantLifecycleResult result) {

    public boolean explicitRecoveryPending() {
        return recoveryStartedAt != null && iamRecoveryConfirmedAt == null;
    }
}
