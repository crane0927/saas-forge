package io.saasforge.sdk.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** 兼容既有接收端的纯验签入口；生产接收端可逐步迁移到 {@link ServiceAccessTokenAuthorizer}。 */
public final class ServiceAccessTokenVerifier {
    private final ServiceAccessTokenSignatureVerifier signatures;

    public ServiceAccessTokenVerifier(
            ServiceJwtVerificationKeyResolver keys,
            Clock clock,
            String issuer,
            String audience,
            Duration clockSkew) {
        this.signatures = new ServiceAccessTokenSignatureVerifier(keys, clock, issuer, audience, clockSkew);
    }

    public ServiceAccessTokenClaims verify(String token, UUID expectedClientId, String requiredScope) {
        return signatures.verify(token, expectedClientId, requiredScope).authorizedClaims();
    }

    public ServiceAccessTokenClaims verify(String token, String requiredScope) {
        return signatures.verify(token, requiredScope).authorizedClaims();
    }
}
