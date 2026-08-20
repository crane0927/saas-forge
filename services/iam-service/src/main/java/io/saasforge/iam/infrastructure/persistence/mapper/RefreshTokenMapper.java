package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.RefreshTokenFamilyRow;
import io.saasforge.iam.infrastructure.persistence.record.RefreshTokenRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RefreshTokenMapper {

    RefreshTokenFamilyRow insertFamily(@Param("row") RefreshTokenFamilyRow row);

    RefreshTokenRow insertToken(@Param("row") RefreshTokenRow row);

    RefreshTokenFamilyRow findFamilyById(@Param("familyId") UUID familyId);

    RefreshTokenRow findTokenByDigest(@Param("tokenDigest") byte[] tokenDigest);

    RefreshTokenRow lockTokenById(@Param("tokenId") UUID tokenId);

    RefreshTokenRow lockTokenByDigest(@Param("tokenDigest") byte[] tokenDigest);

    RefreshTokenFamilyRow lockFamilyById(@Param("familyId") UUID familyId);

    int markTokenConsumed(@Param("tokenId") UUID tokenId, @Param("consumedAt") OffsetDateTime consumedAt);

    int updateFamily(@Param("row") RefreshTokenFamilyRow row);

    int recordRotation(
            @Param("tokenId") UUID tokenId,
            @Param("rotationKeyDigest") byte[] rotationKeyDigest,
            @Param("recoveryExpiresAt") OffsetDateTime recoveryExpiresAt,
            @Param("successorTokenId") UUID successorTokenId,
            @Param("successorAccessJti") UUID successorAccessJti);

    int markRecovered(@Param("tokenId") UUID tokenId, @Param("recoveredAt") OffsetDateTime recoveredAt);
}
