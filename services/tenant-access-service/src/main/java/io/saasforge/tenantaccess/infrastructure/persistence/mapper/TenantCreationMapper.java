package io.saasforge.tenantaccess.infrastructure.persistence.mapper;

import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantCreationIdempotencyRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantAccessOutboxRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface TenantCreationMapper {
    String setOperationTarget(@Param("tenantId") UUID tenantId);

    int insertTenant(@Param("row") TenantRow row);

    int deleteExpiredIdempotency(
            @Param("callerIdentityId") UUID callerIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("now") OffsetDateTime now);

    int claimIdempotency(@Param("row") TenantCreationIdempotencyRow row);

    TenantCreationIdempotencyRow findIdempotency(
            @Param("callerIdentityId") UUID callerIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey);

    int completeIdempotency(@Param("row") TenantCreationIdempotencyRow row);

    int insertOutbox(@Param("row") TenantAccessOutboxRow row);
}
