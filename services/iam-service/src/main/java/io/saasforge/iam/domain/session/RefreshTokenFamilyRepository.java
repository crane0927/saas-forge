package io.saasforge.iam.domain.session;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Refresh Token Family 的持久化边界；所有输入凭据均已是摘要。 */
public interface RefreshTokenFamilyRepository {

    RefreshTokenFamily create(RefreshTokenFamily family, Sha256Digest tokenDigest, Instant issuedAt);

    Optional<RefreshTokenFamily> findById(UUID familyId);

    Optional<RefreshTokenFamily> findUsableSelectionByTokenDigest(Sha256Digest tokenDigest, Instant at);

    Optional<RefreshTokenFamily> findUsableByTokenDigest(Sha256Digest tokenDigest, Instant at);

    Optional<RefreshTokenFamily> findByTokenDigest(Sha256Digest tokenDigest);

    RefreshRotation rotateForRefresh(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            Sha256Digest idempotencyKeyDigest,
            UUID membershipId,
            UUID tenantId,
            UUID nextAccessJti,
            Duration recoveryWindow,
            Instant at);

    RefreshTokenConsumption consume(Sha256Digest tokenDigest, Instant at);

    RefreshTokenConsumption rotate(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at);

    RefreshTokenConsumption selectTenantContext(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at);

    RefreshTokenConsumption rotateSelection(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            Instant at);

    RefreshTokenConsumption revokeForAuthorizationLoss(Sha256Digest presentedDigest, Instant at);

    RefreshTokenConsumption rejectSelection(Sha256Digest presentedDigest, Instant at);

    RefreshTokenConsumption consumeInitialPasswordChange(Sha256Digest presentedDigest, Instant at);

    /** 撤销 Identity 的全部 INITIAL_PASSWORD_CHANGE Family，并锁住并发初始改密流程。 */
    int revokeInitialPasswordChangeFamilies(UUID identityId, Instant at);

    RefreshTokenConsumption logout(Sha256Digest presentedDigest, Instant at);
}
