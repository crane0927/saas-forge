package io.saasforge.iam.domain.signing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** KMS/HSM Signing Key 元数据的服务内持久化边界。 */
public interface SigningKeyRepository {

    SigningKey savePublished(SigningKey key);

    List<SigningKey> findActiveKeys();

    List<SigningKey> findPublishedVerificationKeys();

    default Optional<SigningKey> findActive() {
        List<SigningKey> activeKeys = findActiveKeys();
        if (activeKeys.size() > 1) {
            throw new IllegalStateException("存在多个 ACTIVE Signing Key");
        }
        return activeKeys.stream().findFirst();
    }

    SigningKey activate(UUID keyId, Instant at);

    SigningKey retire(UUID keyId, Instant at);

    SigningKey revoke(UUID keyId, Instant at);
}
