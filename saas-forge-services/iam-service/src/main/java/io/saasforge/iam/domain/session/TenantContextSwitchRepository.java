package io.saasforge.iam.domain.session;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TenantContextSwitchRepository {

    /** 在任何 Tenant Access 调用前锁定 Family 并创建或恢复唯一根工作流。 */
    TenantContextSwitchClaim claim(
            UUID familyId,
            long expectedContextVersion,
            UUID idempotencyKey,
            UUID targetMembershipId,
            Sha256Digest targetFingerprint,
            Instant createdAt,
            String claimant,
            Instant claimedUntil,
            int maximumAttempts);

    Optional<TenantContextSwitchWorkflow> claimNext(
            String claimant, Instant now, Instant claimedUntil, int maximumAttempts);

    Optional<TenantContextSwitchWorkflow> findById(UUID workflowId);

    void complete(TenantContextSwitchWorkflow workflow, TenantContextSwitchStatus status, Instant completedAt);

    boolean scheduleRetry(TenantContextSwitchWorkflow workflow, Instant retryAt, String failureSummary);

    boolean exhaustRecovery(TenantContextSwitchWorkflow workflow, Instant exhaustedAt, String failureSummary);

    Optional<TenantContextSwitchWorkflow> findAwaitingRefresh(UUID familyId);

    void markAwaitingRefresh(TenantContextSwitchWorkflow workflow, long expectedContextVersion, Instant completedAt);

    void completePostSwitchRefresh(UUID familyId, long contextVersion, boolean authorized, Instant refreshedAt);
}
