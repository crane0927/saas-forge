package io.saasforge.iam.domain.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Identity 与密码凭据聚合的持久化边界。 */
public interface IdentityRepository {

    Identity create(Identity identity);

    /**
     * 创建或按规范化邮箱复用 Identity；复用时保留已持久化的显示名。
     */
    Identity findOrCreate(Identity identity);

    Optional<Identity> findByEmail(NormalizedEmail email);

    PasswordCredential create(PasswordCredential credential);

    /**
     * 原子地创建常规密码凭据，并永久失效且保留指定的初始密码凭据。
     */
    PasswordCredential replaceInitialPassword(PasswordCredential initialCredential, PasswordCredential passwordCredential);

    void invalidate(UUID credentialId, Instant invalidatedAt);

    List<PasswordCredential> findCredentials(UUID identityId);
}
