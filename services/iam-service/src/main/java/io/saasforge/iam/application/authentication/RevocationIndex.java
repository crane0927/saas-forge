package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.DurableRevocation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Redis 撤销热路径；Ready 缺失或为未就绪时读取必须 fail closed。 */
public interface RevocationIndex {
    void revokeJti(UUID jti, Instant expiresAt, Instant at);

    void markNotReady();

    void rebuild(List<DurableRevocation> revocations, Instant at);

    boolean isReady();

    boolean isJtiRevoked(UUID jti);

    boolean isKidRevoked(String kid);

    boolean isTokenRevoked(UUID jti, String kid);
}
