package io.saasforge.sdk.auth;

import java.time.Instant;
import java.util.UUID;

/** 已完成签名、时间与 Claim 白名单校验的 User Access Token；撤销策略由调用方决定。 */
public record VerifiedUserAccessTokenClaims(
        UUID identityId,
        UUID jti,
        String kid,
        Instant issuedAt,
        Instant expiresAt,
        UUID membershipId,
        UUID tenantId) {
}
