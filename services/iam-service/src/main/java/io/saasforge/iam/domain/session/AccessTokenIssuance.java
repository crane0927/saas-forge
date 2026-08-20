package io.saasforge.iam.domain.session;

import java.time.Instant;
import java.util.UUID;

/** Access Token 的最小可撤销签发索引，不保存 JWT 或签名。 */
public record AccessTokenIssuance(
        UUID jti,
        UUID familyId,
        UUID identityId,
        UUID membershipId,
        UUID tenantId,
        String kid,
        Instant issuedAt,
        Instant expiresAt) {

    public AccessTokenIssuance {
        if (jti == null || jti.version() != 7 || familyId == null || identityId == null
                || kid == null || kid.isBlank() || issuedAt == null || expiresAt == null
                || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Access Token Issuance 必要字段不合法");
        }
        if ((membershipId == null) != (tenantId == null)) {
            throw new IllegalArgumentException("Membership 与 Tenant 声明必须成对出现");
        }
    }
}
