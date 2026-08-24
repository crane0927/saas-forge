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

    TenantContextSwitchRow findPendingByFamily(@Param("familyId") UUID familyId);

    TenantContextSwitchRow insert(@Param("row") TenantContextSwitchRow row);

    int complete(
            @Param("workflowId") UUID workflowId,
            @Param("switchStatus") String switchStatus,
            @Param("completedAt") OffsetDateTime completedAt);
}
