package io.saasforge.tenantaccess.infrastructure.persistence.mapper;

import io.saasforge.tenantaccess.infrastructure.persistence.record.AdministratorPasswordSetupWorkflowRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface AdministratorPasswordSetupMapper {
    String setOperationTarget(@Param("tenantId") UUID tenantId);

    TenantRow lockTenant(@Param("tenantId") UUID tenantId);

    UUID findInitialAdministratorIdentityId(@Param("tenantId") UUID tenantId);

    int deleteExpiredWorkflow(
            @Param("actorIdentityId") UUID actorIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("now") OffsetDateTime now);

    int insertWorkflow(@Param("row") AdministratorPasswordSetupWorkflowRow row);

    AdministratorPasswordSetupWorkflowRow findWorkflow(
            @Param("actorIdentityId") UUID actorIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey);

    AdministratorPasswordSetupWorkflowRow lockWorkflow(@Param("workflowId") UUID workflowId);

    UUID claimWorkflow(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("at") OffsetDateTime at,
            @Param("claimedUntil") OffsetDateTime claimedUntil);

    UUID claimNextWorkflow(
            @Param("claimant") String claimant,
            @Param("at") OffsetDateTime at,
            @Param("claimedUntil") OffsetDateTime claimedUntil);

    int scheduleRetry(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("retryAt") OffsetDateTime retryAt,
            @Param("lastFailure") String lastFailure);

    int exhaustRecovery(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("exhaustedAt") OffsetDateTime exhaustedAt,
            @Param("lastFailure") String lastFailure);

    int completeOutcome(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("outcomeCode") String outcomeCode,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("expiresAt") OffsetDateTime expiresAt);
}
