package io.saasforge.sdk.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 已完成签名、时间、Claim 白名单与 Scope 校验的 Service Access Token；撤销策略由调用方决定。 */
public record VerifiedServiceAccessTokenClaims(
        UUID clientId,
        Set<String> scopes,
        UUID jti,
        String kid,
        Instant issuedAt,
        Instant expiresAt) {
    public VerifiedServiceAccessTokenClaims {
        scopes = Set.copyOf(scopes);
    }

    ServiceAccessTokenClaims authorizedClaims() {
        return new ServiceAccessTokenClaims(clientId, scopes, jti, issuedAt, expiresAt);
    }
}
