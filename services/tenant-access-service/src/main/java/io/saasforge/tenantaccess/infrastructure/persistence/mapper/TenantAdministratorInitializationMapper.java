package io.saasforge.tenantaccess.infrastructure.persistence.mapper;

import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantAdministratorInitializationRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface TenantAdministratorInitializationMapper {
    String setOperationTarget(@Param("tenantId") UUID tenantId);

    TenantRow lockTenant(@Param("tenantId") UUID tenantId);

    int deleteExpiredWorkflow(
            @Param("actorIdentityId") UUID actorIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("now") OffsetDateTime now);

    int insertWorkflow(@Param("row") TenantAdministratorInitializationRow row);

    TenantAdministratorInitializationRow findWorkflow(
            @Param("actorIdentityId") UUID actorIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey);

    TenantAdministratorInitializationRow lockWorkflow(@Param("workflowId") UUID workflowId);

    UUID claimWorkflow(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("at") OffsetDateTime at,
            @Param("claimedUntil") OffsetDateTime claimedUntil);

    UUID claimNextWorkflow(
            @Param("claimant") String claimant,
            @Param("at") OffsetDateTime at,
            @Param("claimedUntil") OffsetDateTime claimedUntil);

    int completeIdentity(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("administratorIdentityId") UUID administratorIdentityId,
            @Param("credentialDisposition") String credentialDisposition,
            @Param("completedAt") OffsetDateTime completedAt);

    int transitionState(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("expectedState") String expectedState,
            @Param("nextState") String nextState,
            @Param("transitionedAt") OffsetDateTime transitionedAt);

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

    int completeCompensation(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("expiresAt") OffsetDateTime expiresAt);

    int completeFailure(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("outcomeCode") String outcomeCode,
            @Param("responseStatus") int responseStatus,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("expiresAt") OffsetDateTime expiresAt);

    int insertMembership(
            @Param("membershipId") UUID membershipId,
            @Param("tenantId") UUID tenantId,
            @Param("identityId") UUID identityId);

    UUID findMembershipId(@Param("tenantId") UUID tenantId, @Param("identityId") UUID identityId);

    int enableMembership(@Param("membershipId") UUID membershipId);

    int insertAdministratorRole(
            @Param("roleId") UUID roleId,
            @Param("tenantId") UUID tenantId,
            @Param("createdAt") OffsetDateTime createdAt);

    UUID findAdministratorRoleId(@Param("tenantId") UUID tenantId);

    int insertRoleAssignment(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("roleId") UUID roleId,
            @Param("assignedAt") OffsetDateTime assignedAt);

    int insertInitialAdministrator(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("establishedAt") OffsetDateTime establishedAt);

    UUID findInitialAdministratorMembershipId(@Param("tenantId") UUID tenantId);

    int activateTenant(@Param("tenantId") UUID tenantId, @Param("updatedAt") OffsetDateTime updatedAt);

    int insertPasswordDeliveryWorkItem(
            @Param("workflowId") UUID workflowId,
            @Param("tenantId") UUID tenantId,
            @Param("identityId") UUID identityId,
            @Param("deliveryRequestId") UUID deliveryRequestId,
            @Param("createdAt") OffsetDateTime createdAt);

    int completeSuccess(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("responseBody") String responseBody,
            @Param("releaseLease") boolean releaseLease,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("expiresAt") OffsetDateTime expiresAt);

    int completePasswordDelivery(
            @Param("workflowId") UUID workflowId,
            @Param("claimant") String claimant,
            @Param("attemptCount") int attemptCount,
            @Param("completedAt") OffsetDateTime completedAt);
}
