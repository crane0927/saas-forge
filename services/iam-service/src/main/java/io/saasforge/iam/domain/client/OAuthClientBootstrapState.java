package io.saasforge.iam.domain.client;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.List;

/** 保留 Client 引导重放所需的完整持久状态。 */
public record OAuthClientBootstrapState(OAuthClient client, List<SecretState> secrets) {
    public OAuthClientBootstrapState {
        secrets = List.copyOf(secrets);
    }

    public boolean hasCurrentSecret(Sha256Digest expectedDigest, Instant at) {
        return secrets.stream().anyMatch(secret -> secret.digest().equals(expectedDigest)
                && secret.revokedAt() == null
                && (secret.validUntil() == null || secret.validUntil().isAfter(at)));
    }

    public record SecretState(Sha256Digest digest, Instant validUntil, Instant revokedAt) {
    }
}
