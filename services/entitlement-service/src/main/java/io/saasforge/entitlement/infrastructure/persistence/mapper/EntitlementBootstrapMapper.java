package io.saasforge.entitlement.infrastructure.persistence.mapper;

import io.saasforge.entitlement.infrastructure.persistence.record.EntitlementIdempotencyRow;
import io.saasforge.entitlement.infrastructure.persistence.record.EntitlementOutboxRow;
import io.saasforge.entitlement.infrastructure.persistence.record.PlanQuotaLimitRow;
import io.saasforge.entitlement.infrastructure.persistence.record.PlanRow;
import io.saasforge.entitlement.infrastructure.persistence.record.QuotaDefinitionRow;
import io.saasforge.entitlement.infrastructure.persistence.record.SubscriptionRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface EntitlementBootstrapMapper {
    String setOperationTarget(@Param("tenantId") UUID tenantId);

    int insertQuotaDefinition(@Param("row") QuotaDefinitionRow row);

    QuotaDefinitionRow findQuotaDefinition(@Param("id") UUID id);

    int activateQuotaDefinition(@Param("id") UUID id, @Param("updatedAt") OffsetDateTime updatedAt);

    int insertPlan(@Param("row") PlanRow row);

    int insertPlanQuotaLimit(@Param("row") PlanQuotaLimitRow row);

    PlanRow findPlan(@Param("id") UUID id);

    List<PlanQuotaLimitRow> findPlanQuotaLimits(@Param("planId") UUID planId);

    int activatePlan(@Param("id") UUID id, @Param("updatedAt") OffsetDateTime updatedAt);

    int insertSubscription(@Param("row") SubscriptionRow row);

    int deleteExpiredIdempotency(
            @Param("callerIdentityId") UUID callerIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("now") OffsetDateTime now);

    int claimIdempotency(@Param("row") EntitlementIdempotencyRow row);

    EntitlementIdempotencyRow findIdempotency(
            @Param("callerIdentityId") UUID callerIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey);

    int completeIdempotency(@Param("row") EntitlementIdempotencyRow row);

    int insertOutbox(@Param("row") EntitlementOutboxRow row);
}
