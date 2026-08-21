package io.saasforge.iam.domain.client;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.List;

/** 保留 Client 引导重放所需的完整持久状态。 */
public record OAuthClientBootstrapState(OAuthClient client, List<SecretState> secrets) {
    public OAuthClientBootstrapState {
        secrets = List.copyOf(secrets);
    }

    public boolean exactlyMatches(Sha256Digest expectedDigest) {
        return client.status() == OAuthClientStatus.ACTIVE
                && client.revokedAt() == null
                && secrets.size() == 1
                && secrets.get(0).digest().equals(expectedDigest)
                && secrets.get(0).validUntil() == null
                && secrets.get(0).revokedAt() == null;
    }

    public record SecretState(Sha256Digest digest, Instant validUntil, Instant revokedAt) {
    }
}
