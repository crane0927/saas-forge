package io.saasforge.iam.domain.client;

import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** OAuth Client 与其 Secret 生命周期的持久化边界。 */
public interface OAuthClientRepository {

    OAuthClientCreation create(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt);

    OAuthClient createWithId(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt);

    void lockReservedClientBootstrap();

    Optional<OAuthClientBootstrapState> findBootstrapState(UUID clientId);

    Optional<OAuthClient> findById(UUID clientId);

    Optional<OAuthClient> findActiveByReservedServiceKey(ReservedServiceKey serviceKey);

    Optional<OAuthClient> findAnyByReservedServiceKey(ReservedServiceKey serviceKey);

    Optional<OAuthClient> findActiveBySecretDigest(Sha256Digest secretDigest, Instant at);

    ClientSecret rotate(UUID clientId, Sha256Digest nextSecretDigest, Instant at);

    /** 原子替代原签发操作产生的 Secret，并保留其他 Secret 的既有截止时间。 */
    ClientSecret recover(UUID clientId, UUID originalSecretId, Sha256Digest replacementDigest, Instant at);

    /** 返回 true 表示本次固定了首次不可逆吊销事实。 */
    boolean revoke(UUID clientId, Instant at);

    List<UUID> findRevokedClientIds();
}
