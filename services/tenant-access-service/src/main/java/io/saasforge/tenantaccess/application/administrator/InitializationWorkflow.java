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
        Instant createdAt) {

    public boolean completed() {
        return outcomeCode != null;
    }
}
