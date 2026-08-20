package io.saasforge.iam.domain.signing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** KMS/HSM Signing Key 元数据的服务内持久化边界。 */
public interface SigningKeyRepository {

    SigningKey savePublished(SigningKey key);

    Optional<SigningKey> findActive();

    SigningKey activate(UUID keyId, Instant at);

    SigningKey retire(UUID keyId, Instant at);

    SigningKey revoke(UUID keyId, Instant at);
}
