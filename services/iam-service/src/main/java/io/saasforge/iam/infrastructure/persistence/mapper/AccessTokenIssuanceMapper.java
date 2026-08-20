package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.AccessTokenIssuanceRow;
import io.saasforge.iam.infrastructure.persistence.record.DurableRevocationRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface AccessTokenIssuanceMapper {
    int insert(@Param("row") AccessTokenIssuanceRow row);

    AccessTokenIssuanceRow findByJti(@Param("jti") UUID jti);

    int revoke(
            @Param("jti") UUID jti,
            @Param("revokedAt") OffsetDateTime revokedAt,
            @Param("reason") String reason);

    List<DurableRevocationRow> findUnexpiredRevocations(@Param("at") OffsetDateTime at);

    List<AccessTokenIssuanceRow> findUnexpiredByFamilyId(
            @Param("familyId") UUID familyId, @Param("at") OffsetDateTime at);
}
