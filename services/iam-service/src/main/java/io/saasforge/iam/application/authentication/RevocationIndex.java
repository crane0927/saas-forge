package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.DurableRevocation;
import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.RevocationFence;
import io.saasforge.iam.domain.session.RevocationFenceTarget;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Redis 撤销热路径；Ready 缺失或为未就绪时读取必须 fail closed。 */
public interface RevocationIndex {
    void revokeJti(UUID jti, Instant expiresAt, Instant at);

    /** 原子写入失陷 kid 及其全部未过期 jti，任一写入失败都不得部分报告成功。 */
    void revokeSigningKey(String kid, Instant rejectUntil, List<AccessTokenIssuance> issuances, Instant at);

    void markNotReady();

    void rebuild(List<DurableRevocation> revocations, List<RevocationFence> fences, Instant at);

    void establishFence(RevocationFence fence);

    boolean isUserTokenFenced(RevocationFenceTarget target);

    boolean isReady();

    boolean isJtiRevoked(UUID jti);

    boolean isKidRevoked(String kid);

    boolean isTokenRevoked(UUID jti, String kid);
}
