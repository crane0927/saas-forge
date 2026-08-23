package io.saasforge.tenantaccess.application.administrator;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TenantAdministratorInitializationRepository {
    InitializationWorkflow prepare(InitializationWorkflow candidate, Instant now);

    Optional<InitializationWorkflow> claim(UUID workflowId, String claimant, Instant now, Instant claimedUntil);

    Optional<InitializationWorkflow> claimNext(String claimant, Instant now, Instant claimedUntil);

    InitializationWorkflow completeIdentity(
            InitializationWorkflow workflow,
            UUID administratorIdentityId,
            IdentityCredentialDisposition credentialDisposition,
            Instant completedAt);

    InitializationWorkflow completeQuotaConsumption(InitializationWorkflow workflow, Instant completedAt);

    InitializationWorkflow beginActivation(InitializationWorkflow workflow, Instant startedAt);

    InitializationWorkflow beginCompensation(InitializationWorkflow workflow, Instant startedAt);

    void scheduleRetry(InitializationWorkflow workflow, Instant retryAt, String failureSummary);

    /** 暂停后台自动领取但保留原工作流；显式重放原 Idempotency-Key 可重新领取。 */
    void exhaustRecovery(InitializationWorkflow workflow, Instant exhaustedAt, String failureSummary);

    void completeCompensation(InitializationWorkflow workflow, Instant completedAt);

    void completeFailure(InitializationWorkflow workflow, String outcomeCode, Instant completedAt);

    TenantAdministratorInitializationResult activate(
            InitializationWorkflow workflow,
            UUID administratorIdentityId,
            IdentityCredentialDisposition credentialDisposition,
            Instant activatedAt);

    void completePasswordDelivery(InitializationWorkflow workflow, Instant completedAt);
}
