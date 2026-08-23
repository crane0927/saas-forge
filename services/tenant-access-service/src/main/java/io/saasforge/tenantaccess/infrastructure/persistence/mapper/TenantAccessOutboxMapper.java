package io.saasforge.tenantaccess.infrastructure.persistence.mapper;

import io.saasforge.tenantaccess.infrastructure.persistence.record.ClaimedTenantAccessOutboxRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface TenantAccessOutboxMapper {
    String setOperationTarget(@Param("tenantId") UUID tenantId);

    UUID claimNext(
            @Param("claimant") String claimant,
            @Param("at") OffsetDateTime at,
            @Param("claimedUntil") OffsetDateTime claimedUntil);

    ClaimedTenantAccessOutboxRow findClaimed(@Param("eventId") UUID eventId);

    int markPublished(
            @Param("eventId") UUID eventId,
            @Param("claimant") String claimant,
            @Param("publishedAt") OffsetDateTime publishedAt);

    int releaseAfterFailure(
            @Param("eventId") UUID eventId,
            @Param("claimant") String claimant,
            @Param("retryAt") OffsetDateTime retryAt,
            @Param("failureSummary") String failureSummary);
}
