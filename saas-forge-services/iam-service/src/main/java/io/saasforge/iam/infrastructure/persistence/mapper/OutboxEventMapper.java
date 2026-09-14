package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.OutboxEventRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface OutboxEventMapper {
    int insert(@Param("row") OutboxEventRow row);

    OutboxEventRow claimNext(
            @Param("claimant") String claimant,
            @Param("at") OffsetDateTime at,
            @Param("claimedUntil") OffsetDateTime claimedUntil);

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
