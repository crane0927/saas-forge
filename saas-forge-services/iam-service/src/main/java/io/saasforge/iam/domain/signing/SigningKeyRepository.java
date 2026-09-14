package io.saasforge.iam.domain.signing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** KMS/HSM Signing Key 元数据的服务内持久化边界。 */
public interface SigningKeyRepository {

    SigningKey savePublished(SigningKey key);

    List<SigningKey> findActiveKeys();

    List<SigningKey> findPublishedVerificationKeys();

    Optional<SigningKey> findById(UUID keyId);

    default Optional<SigningKey> findActive() {
        List<SigningKey> activeKeys = findActiveKeys();
        if (activeKeys.size() > 1) {
            throw new IllegalStateException("存在多个 ACTIVE Signing Key");
        }
        return activeKeys.stream().findFirst();
    }

    SigningKey activate(UUID keyId, Instant at);

    /** 在签名前原子、单调地提高 ACTIVE key 见过的最大 Token TTL。 */
    SigningKey prepareActiveForIssuance(Duration tokenTtl);

    SigningKey retire(UUID keyId, Instant at);

    SigningKey revoke(UUID keyId, Instant at);

    /** 撤销 ACTIVE key 时原子启用已完成发布窗口的替代 key。 */
    SigningKey revoke(UUID keyId, UUID replacementKeyId, Instant at);
}
