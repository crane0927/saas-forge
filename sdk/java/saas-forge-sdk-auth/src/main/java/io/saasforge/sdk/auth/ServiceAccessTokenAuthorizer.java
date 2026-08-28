package io.saasforge.sdk.auth;

import java.util.UUID;

/** 在返回 Service Access Token Claims 前组合纯验签与运行时撤销状态检查。 */
public final class ServiceAccessTokenAuthorizer {
    private final ServiceAccessTokenSignatureVerifier signatures;
    private final ServiceAccessTokenRevocationChecker revocations;

    public ServiceAccessTokenAuthorizer(
            ServiceAccessTokenSignatureVerifier signatures,
            ServiceAccessTokenRevocationChecker revocations) {
        if (signatures == null || revocations == null) {
            throw new IllegalArgumentException("Service Access Token 授权配置不合法");
        }
        this.signatures = signatures;
        this.revocations = revocations;
    }

    public ServiceAccessTokenClaims authorize(
            String token, UUID expectedClientId, String requiredScope) {
        return authorize(signatures.verify(token, expectedClientId, requiredScope));
    }

    public ServiceAccessTokenClaims authorize(String token, String requiredScope) {
        return authorize(signatures.verify(token, requiredScope));
    }

    private ServiceAccessTokenClaims authorize(VerifiedServiceAccessTokenClaims claims) {
        try {
            if (revocations.isRevoked(claims.clientId(), claims.kid())) {
                throw new ServiceAccessTokenInvalidException();
            }
            return claims.authorizedClaims();
        } catch (ServiceAccessTokenInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            // Redis、Ready 或撤销状态不可判定时不得退化为纯离线验签。
            throw new ServiceAccessTokenInvalidException(exception);
        }
    }
}
