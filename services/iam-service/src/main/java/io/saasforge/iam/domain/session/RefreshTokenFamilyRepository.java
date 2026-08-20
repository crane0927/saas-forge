package io.saasforge.iam.domain.session;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Refresh Token Family 的持久化边界；所有输入凭据均已是摘要。 */
public interface RefreshTokenFamilyRepository {

    RefreshTokenFamily create(RefreshTokenFamily family, Sha256Digest tokenDigest, Instant issuedAt);

    Optional<RefreshTokenFamily> findById(UUID familyId);

    Optional<RefreshTokenFamily> findUsableSelectionByTokenDigest(Sha256Digest tokenDigest, Instant at);

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

    RefreshTokenConsumption rejectSelection(Sha256Digest presentedDigest, Instant at);

    RefreshTokenConsumption consumeInitialPasswordChange(Sha256Digest presentedDigest, Instant at);
}
