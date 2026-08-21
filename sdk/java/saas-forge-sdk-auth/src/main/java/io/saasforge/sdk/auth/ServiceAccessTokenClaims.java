package io.saasforge.sdk.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ServiceAccessTokenClaims(
        UUID clientId,
        Set<String> scopes,
        UUID jti,
        Instant issuedAt,
        Instant expiresAt) {
    public ServiceAccessTokenClaims {
        scopes = Set.copyOf(scopes);
    }
}
