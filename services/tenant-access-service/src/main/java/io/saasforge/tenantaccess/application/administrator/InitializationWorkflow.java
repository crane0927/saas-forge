package io.saasforge.tenantaccess.application.administrator;

import java.time.Instant;
import java.util.UUID;

public record InitializationWorkflow(
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
        TenantAdministratorInitializationResult result,
        Instant createdAt,
        InitializationWorkflowState state,
        UUID administratorIdentityId,
        IdentityCredentialDisposition credentialDisposition,
        boolean passwordDeliveryPending,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil) {

    public InitializationWorkflow(
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
            TenantAdministratorInitializationResult result,
            Instant createdAt) {
        this(workflowId, tenantId, actorIdentityId, idempotencyKey, requestFingerprint,
                administratorEmail, administratorDisplayName, identityRequestId, consumeOperationId,
                releaseOperationId, passwordDeliveryRequestId, traceId, outcomeCode, result, createdAt,
                outcomeCode == null ? InitializationWorkflowState.PREPARED
                        : "SUCCESS".equals(outcomeCode)
                                ? InitializationWorkflowState.SUCCEEDED : InitializationWorkflowState.FAILED,
                null, null, false, 0, createdAt, null, null);
    }

    public boolean completed() {
        return outcomeCode != null;
    }

    public boolean leasedBy(String claimant) {
        return claimant != null && claimant.equals(leaseOwner);
    }
}
