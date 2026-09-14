package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.identity.PasswordSetupChallenge;
import io.saasforge.iam.domain.identity.PasswordSetupChallengeRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.PasswordSetupChallengeMapper;
import io.saasforge.iam.infrastructure.persistence.record.PasswordSetupChallengeRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPasswordSetupChallengeRepository implements PasswordSetupChallengeRepository {
    private final PasswordSetupChallengeMapper mapper;

    public MyBatisPasswordSetupChallengeRepository(PasswordSetupChallengeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PasswordSetupChallenge> replaceOpenChallenge(
            UUID identityId, Sha256Digest tokenDigest, Instant issuedAt, Instant expiresAt) {
        if (mapper.lockEligibleIdentity(identityId) == null) {
            return Optional.empty();
        }
        mapper.invalidateOpenChallenges(identityId, IamTime.asOffsetDateTime(issuedAt));
        PasswordSetupChallengeRow row = new PasswordSetupChallengeRow();
        row.setIdentityId(identityId);
        row.setTokenDigest(tokenDigest.value());
        row.setIssuedAt(IamTime.asOffsetDateTime(issuedAt));
        row.setExpiresAt(IamTime.asOffsetDateTime(expiresAt));
        return Optional.of(mapper.insertChallenge(row)).map(MyBatisPasswordSetupChallengeRepository::toDomain);
    }

    @Override
    public Optional<PasswordSetupChallenge> findByTokenDigest(Sha256Digest tokenDigest) {
        return Optional.ofNullable(mapper.findByTokenDigest(tokenDigest.value()))
                .map(MyBatisPasswordSetupChallengeRepository::toDomain);
    }

    @Override
    public Optional<PasswordSetupChallenge> lockByTokenDigest(Sha256Digest tokenDigest) {
        return Optional.ofNullable(mapper.lockByTokenDigest(tokenDigest.value()))
                .map(MyBatisPasswordSetupChallengeRepository::toDomain);
    }

    @Override
    public void complete(
            UUID challengeId, UUID idempotencyKey, Sha256Digest requestFingerprint,
            UUID credentialId, Instant consumedAt) {
        if (mapper.complete(challengeId, idempotencyKey, requestFingerprint.value(), credentialId,
                IamTime.asOffsetDateTime(consumedAt)) != 1) {
            throw new IllegalStateException("Password Setup Challenge 无法完成消费");
        }
    }

    private static PasswordSetupChallenge toDomain(PasswordSetupChallengeRow row) {
        return new PasswordSetupChallenge(
                row.getId(), row.getIdentityId(), Sha256Digest.of(row.getTokenDigest()),
                IamTime.asInstant(row.getIssuedAt()), IamTime.asInstant(row.getExpiresAt()),
                IamTime.asInstant(row.getInvalidatedAt()), IamTime.asInstant(row.getConsumedAt()),
                row.getIdempotencyKey(), row.getRequestFingerprint() == null
                        ? null : Sha256Digest.of(row.getRequestFingerprint()),
                row.getCredentialId(), row.getCompletedStatus());
    }
}
