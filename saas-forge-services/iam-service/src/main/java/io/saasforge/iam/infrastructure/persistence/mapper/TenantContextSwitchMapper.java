package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.TenantContextSwitchRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface TenantContextSwitchMapper {
    Long lockFamilyContextVersion(@Param("familyId") UUID familyId);

    TenantContextSwitchRow findByFamilyAndKey(
            @Param("familyId") UUID familyId,
            @Param("idempotencyKey") UUID idempotencyKey);

    TenantContextSwitchRow findBlockingByFamily(@Param("familyId") UUID familyId);

    TenantContextSwitchRow findAwaitingRefreshByFamily(@Param("familyId") UUID familyId);

    TenantContextSwitchRow findById(@Param("workflowId") UUID workflowId);

    TenantContextSwitchRow findNextClaimable(
            @Param("now") OffsetDateTime now,
            @Param("maximumAttempts") int maximumAttempts);

    int exhaustExpiredAtLimit(
            @Param("now") OffsetDateTime now,
            @Param("maximumAttempts") int maximumAttempts,
            @Param("failureSummary") String failureSummary);

    int exhaustWorkflowAtLimit(
            @Param("workflowId") UUID workflowId,
            @Param("now") OffsetDateTime now,
            @Param("maximumAttempts") int maximumAttempts,
            @Param("failureSummary") String failureSummary);

    TenantContextSwitchRow insert(@Param("row") TenantContextSwitchRow row);

    TenantContextSwitchRow claimExisting(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("now") OffsetDateTime now,
            @Param("claimedUntil") OffsetDateTime claimedUntil,
            @Param("maximumAttempts") int maximumAttempts);

    int complete(
            @Param("workflowId") UUID workflowId,
            @Param("switchStatus") String switchStatus,
            @Param("resultHttpStatus") Integer resultHttpStatus,
            @Param("attemptCount") int attemptCount,
            @Param("completedAt") OffsetDateTime completedAt);

    int scheduleRetry(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("retryAt") OffsetDateTime retryAt,
            @Param("failureSummary") String failureSummary);

    int exhaustRecovery(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("exhaustedAt") OffsetDateTime exhaustedAt,
            @Param("failureSummary") String failureSummary);

    int markAwaitingRefresh(
            @Param("workflowId") UUID workflowId,
            @Param("expectedContextVersion") long expectedContextVersion,
            @Param("attemptCount") int attemptCount,
            @Param("completedAt") OffsetDateTime completedAt);

    int completePostSwitchRefresh(
            @Param("familyId") UUID familyId,
            @Param("contextVersion") long contextVersion,
            @Param("switchStatus") String switchStatus,
            @Param("refreshedAt") OffsetDateTime refreshedAt);
}
