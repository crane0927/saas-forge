package io.saasforge.sdk.auth;

import java.time.Instant;
import java.util.UUID;

/** 已验证且不含 Tenant 上下文的 Platform 形态 User Access Token。 */
public record UserAccessTokenClaims(
        UUID identityId,
        UUID jti,
        String kid,
        Instant issuedAt,
        Instant expiresAt) {
}
