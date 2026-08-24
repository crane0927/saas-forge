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

    TenantContextSwitchRow insert(@Param("row") TenantContextSwitchRow row);

    int complete(
            @Param("workflowId") UUID workflowId,
            @Param("switchStatus") String switchStatus,
            @Param("resultHttpStatus") Integer resultHttpStatus,
            @Param("completedAt") OffsetDateTime completedAt);

    int markAwaitingRefresh(
            @Param("workflowId") UUID workflowId,
            @Param("expectedContextVersion") long expectedContextVersion,
            @Param("completedAt") OffsetDateTime completedAt);

    int completePostSwitchRefresh(
            @Param("familyId") UUID familyId,
            @Param("contextVersion") long contextVersion,
            @Param("switchStatus") String switchStatus,
            @Param("refreshedAt") OffsetDateTime refreshedAt);
}
