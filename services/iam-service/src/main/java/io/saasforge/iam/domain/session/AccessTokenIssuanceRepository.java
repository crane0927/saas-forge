package io.saasforge.iam.domain.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessTokenIssuanceRepository {
    void create(AccessTokenIssuance issuance);

    Optional<AccessTokenIssuance> findByJti(UUID jti);

    boolean revoke(UUID jti, Instant revokedAt, String reason);

    List<DurableRevocation> findUnexpiredRevocations(Instant at);

    List<AccessTokenIssuance> findUnexpiredByFamilyId(UUID familyId, Instant at);
}
