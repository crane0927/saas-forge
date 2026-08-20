package io.saasforge.iam.domain.client;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** OAuth Client 与其 Secret 生命周期的持久化边界。 */
public interface OAuthClientRepository {

    OAuthClient create(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt);

    Optional<OAuthClient> findActiveBySecretDigest(Sha256Digest secretDigest, Instant at);

    ClientSecret rotate(UUID clientId, Sha256Digest nextSecretDigest, Instant at);

    void revoke(UUID clientId, Instant at);
}
