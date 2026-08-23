package io.saasforge.tenantaccess.application.administrator;

import java.time.Instant;
import java.util.UUID;

public interface TenantAdministratorInitializationRepository {
    InitializationWorkflow prepare(InitializationWorkflow candidate, Instant now);

    void completeFailure(UUID tenantId, UUID workflowId, String outcomeCode, Instant completedAt);

    TenantAdministratorInitializationResult activate(
            InitializationWorkflow workflow,
            UUID administratorIdentityId,
            IdentityCredentialDisposition credentialDisposition,
            Instant activatedAt);

    void completePasswordDelivery(UUID tenantId, UUID workflowId, Instant completedAt);
}
