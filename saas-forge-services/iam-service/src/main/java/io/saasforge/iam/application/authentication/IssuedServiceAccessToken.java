package io.saasforge.iam.application.authentication;

import java.time.Instant;
import java.util.UUID;

public record IssuedServiceAccessToken(
        String value,
        UUID jti,
        String kid,
        Instant issuedAt,
        Instant expiresAt,
        long expiresInSeconds,
        String scope) {
}
