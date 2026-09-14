package io.saasforge.iam.domain.identity;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Password Setup Challenge 的替换、锁定和成功消费边界。 */
public interface PasswordSetupChallengeRepository {

    Optional<PasswordSetupChallenge> replaceOpenChallenge(
            UUID identityId, Sha256Digest tokenDigest, Instant issuedAt, Instant expiresAt);

    Optional<PasswordSetupChallenge> findByTokenDigest(Sha256Digest tokenDigest);

    Optional<PasswordSetupChallenge> lockByTokenDigest(Sha256Digest tokenDigest);

    void complete(
            UUID challengeId,
            UUID idempotencyKey,
            Sha256Digest requestFingerprint,
            UUID credentialId,
            Instant consumedAt);
}
