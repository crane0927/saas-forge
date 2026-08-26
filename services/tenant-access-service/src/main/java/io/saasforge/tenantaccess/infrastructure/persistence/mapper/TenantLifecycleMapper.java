package io.saasforge.tenantaccess.infrastructure.persistence.mapper;

import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantLifecycleRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantSuspensionRecoveryRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface TenantLifecycleMapper {
    String setOperationTarget(@Param("tenantId") UUID tenantId);
    UUID setWorkflowTarget(@Param("workflowId") UUID workflowId);
    TenantRow lockTenant(@Param("tenantId") UUID tenantId);
    TenantRow findTenant(@Param("tenantId") UUID tenantId);
    TenantLifecycleRow find(@Param("workflowId") UUID workflowId);
    TenantLifecycleRow findByExternalKey(
            @Param("actorIdentityId") UUID actorIdentityId, @Param("idempotencyKey") UUID idempotencyKey);
    TenantLifecycleRow findActive(@Param("tenantId") UUID tenantId);
    UUID findLatestSuspensionRequest(@Param("tenantId") UUID tenantId);
    int insert(@Param("row") TenantLifecycleRow row, @Param("at") OffsetDateTime at);
    UUID claim(
            @Param("workflowId") UUID workflowId, @Param("claimant") String claimant,
            @Param("at") OffsetDateTime at, @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("maximumAttempts") int maximumAttempts);
    UUID claimNext(
            @Param("claimant") String claimant, @Param("at") OffsetDateTime at,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("maximumAttempts") int maximumAttempts);
    int confirmFence(@Param("workflowId") UUID workflowId, @Param("fencingToken") long fencingToken);
    int markRevocationAttempt(@Param("workflowId") UUID workflowId, @Param("fencingToken") long fencingToken);
    int confirmIamRecovery(
            @Param("workflowId") UUID workflowId, @Param("fencingToken") long fencingToken,
            @Param("at") OffsetDateTime at);
    int schedulePending(
            @Param("workflowId") UUID workflowId, @Param("fencingToken") long fencingToken,
            @Param("retryAt") OffsetDateTime retryAt);
    int scheduleFailure(
            @Param("workflowId") UUID workflowId, @Param("fencingToken") long fencingToken,
            @Param("retryAt") OffsetDateTime retryAt, @Param("failure") String failure,
            @Param("maximumAttempts") int maximumAttempts,
            @Param("fenceMayExist") boolean fenceMayExist,
            @Param("at") OffsetDateTime at);
    int transitionTenant(
            @Param("tenantId") UUID tenantId, @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus, @Param("updatedAt") OffsetDateTime updatedAt);
    int complete(
            @Param("workflowId") UUID workflowId, @Param("fencingToken") long fencingToken,
            @Param("revokedFamilyCount") long revokedFamilyCount,
            @Param("revokedJtiCount") long revokedJtiCount,
            @Param("responseBody") String responseBody, @Param("at") OffsetDateTime at);
    TenantSuspensionRecoveryRow findRecovery(
            @Param("actorIdentityId") UUID actorIdentityId, @Param("idempotencyKey") UUID idempotencyKey);
    int insertRecovery(
            @Param("actorIdentityId") UUID actorIdentityId, @Param("idempotencyKey") UUID idempotencyKey,
            @Param("tenantId") UUID tenantId, @Param("workflowId") UUID workflowId,
            @Param("fingerprint") String fingerprint, @Param("at") OffsetDateTime at);
    int startRecovery(@Param("workflowId") UUID workflowId, @Param("at") OffsetDateTime at);
    int completeRecoveries(
            @Param("workflowId") UUID workflowId, @Param("responseBody") String responseBody,
            @Param("at") OffsetDateTime at);
}
