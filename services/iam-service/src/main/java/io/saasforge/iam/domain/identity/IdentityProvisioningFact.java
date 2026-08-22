package io.saasforge.iam.domain.identity;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.UUID;

/** IAM 对一次调用方请求作出的不可变 Identity 确保结论。 */
public record IdentityProvisioningFact(
        UUID callerClientId,
        UUID requestId,
        Sha256Digest requestFingerprint,
        UUID identityId,
        IdentityCredentialStatus credentialStatus,
        Instant ensuredAt) {

    public IdentityProvisioningFact {
        if (callerClientId == null || callerClientId.version() != 7
                || requestId == null || requestId.version() != 7
                || requestFingerprint == null
                || identityId == null || identityId.version() != 7
                || credentialStatus == null || ensuredAt == null) {
            throw new IllegalArgumentException("Identity 确保事实不合法");
        }
    }
}
