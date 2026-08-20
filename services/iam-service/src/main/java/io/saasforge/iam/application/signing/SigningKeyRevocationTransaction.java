package io.saasforge.iam.application.signing;

import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class SigningKeyRevocationTransaction {
    private static final String REVOCATION_REASON = "SIGNING_KEY_COMPROMISED";

    private final SigningKeyRepository signingKeys;
    private final AccessTokenIssuanceRepository issuances;

    public SigningKeyRevocationTransaction(
            SigningKeyRepository signingKeys, AccessTokenIssuanceRepository issuances) {
        this.signingKeys = signingKeys;
        this.issuances = issuances;
    }

    /** Signing Key 撤销事实和该 key 的未过期 jti 撤销事实必须原子提交。 */
    @Transactional
    public SigningKey commit(UUID keyId, UUID replacementKeyId, Instant at) {
        SigningKey revoked = signingKeys.revoke(keyId, replacementKeyId, at);
        issuances.revokeUnexpiredByKid(revoked.kid(), at, REVOCATION_REASON);
        return revoked;
    }
}
