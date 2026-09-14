package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.PasswordSetupChallengeRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface PasswordSetupChallengeMapper {
    UUID lockEligibleIdentity(@Param("identityId") UUID identityId);
    int invalidateOpenChallenges(
            @Param("identityId") UUID identityId,
            @Param("invalidatedAt") OffsetDateTime invalidatedAt);
    PasswordSetupChallengeRow insertChallenge(@Param("row") PasswordSetupChallengeRow row);
    PasswordSetupChallengeRow findByTokenDigest(@Param("tokenDigest") byte[] tokenDigest);
    PasswordSetupChallengeRow lockByTokenDigest(@Param("tokenDigest") byte[] tokenDigest);
    int complete(
            @Param("challengeId") UUID challengeId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestFingerprint") byte[] requestFingerprint,
            @Param("credentialId") UUID credentialId,
            @Param("consumedAt") OffsetDateTime consumedAt);
}
