package io.saasforge.iam.domain.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Identity 与密码凭据聚合的持久化边界。 */
public interface IdentityRepository {

    Identity save(Identity identity);

    Optional<Identity> findByEmail(NormalizedEmail email);

    PasswordCredential save(PasswordCredential credential);

    void invalidate(UUID credentialId, Instant invalidatedAt);

    List<PasswordCredential> findCredentials(UUID identityId);
}
