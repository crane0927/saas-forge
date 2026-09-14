package io.saasforge.sdk.auth;

import java.time.Clock;
import java.time.Duration;

/** 严格校验 Platform 形态 User Access Token，拒绝 Tenant、Role 或 Permission 声明。 */
public final class UserAccessTokenVerifier {
    private final UserAccessTokenSignatureVerifier signatures;
    private final UserAccessTokenRevocationChecker revocations;

    public UserAccessTokenVerifier(
            ServiceJwtVerificationKeyResolver keys,
            UserAccessTokenRevocationChecker revocations,
            Clock clock,
            String issuer,
            String audience,
            Duration clockSkew) {
        this(new UserAccessTokenSignatureVerifier(keys, clock, issuer, audience, clockSkew), revocations);
    }

    UserAccessTokenVerifier(
            UserAccessTokenSignatureVerifier signatures,
            UserAccessTokenRevocationChecker revocations) {
        if (signatures == null || revocations == null) {
            throw new IllegalArgumentException("User Access Token 校验配置不合法");
        }
        this.signatures = signatures;
        this.revocations = revocations;
    }

    public UserAccessTokenClaims verifyPlatformToken(String authorization) {
        VerifiedUserAccessTokenClaims claims = signatures.verify(authorization);
        if (claims.membershipId() != null || claims.tenantId() != null) {
            throw new UserAccessTokenInvalidException();
        }
        try {
            if (revocations.isRevoked(claims.jti(), claims.kid())) {
                throw new UserAccessTokenInvalidException();
            }
            return new UserAccessTokenClaims(
                    claims.identityId(), claims.jti(), claims.kid(), claims.issuedAt(), claims.expiresAt());
        } catch (UserAccessTokenInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UserAccessTokenInvalidException(exception);
        }
    }
}
