package io.saasforge.tenantaccess.application.administrator;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AdministratorPasswordSetupRepository {
    AdministratorPasswordSetupWorkflow prepare(AdministratorPasswordSetupWorkflow candidate, Instant now);

    Optional<AdministratorPasswordSetupWorkflow> claim(
            UUID workflowId, String claimant, Instant now, Instant claimedUntil);

    Optional<AdministratorPasswordSetupWorkflow> claimNext(
            String claimant, Instant now, Instant claimedUntil);

    void scheduleRetry(
            AdministratorPasswordSetupWorkflow workflow, Instant retryAt, String failureSummary);

    void exhaustRecovery(
            AdministratorPasswordSetupWorkflow workflow, Instant exhaustedAt, String failureSummary);

    void completeSuccess(AdministratorPasswordSetupWorkflow workflow, Instant completedAt);

    void completeRecoveryRequired(AdministratorPasswordSetupWorkflow workflow, Instant completedAt);
}
