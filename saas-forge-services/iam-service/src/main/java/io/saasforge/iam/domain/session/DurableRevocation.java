package io.saasforge.iam.domain.session;

import java.time.Instant;
import java.util.UUID;

/** PostgreSQL 中尚未越过安全窗口的 Access Token 或 Signing Key 撤销事实。 */
public record DurableRevocation(
        UUID jti,
        String kid,
        Instant expiresAt,
        boolean jtiRevoked,
        boolean kidRevoked) {

    public DurableRevocation {
        if (jti == null || kid == null || kid.isBlank() || expiresAt == null || (!jtiRevoked && !kidRevoked)) {
            throw new IllegalArgumentException("耐久撤销事实不完整");
        }
    }
}
