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

    /** 按 IAM 权威标识读取 Identity，供不得接受调用方邮箱的内部用例使用。 */
    Optional<Identity> findById(UUID identityId);

    /** 跨 Credential/Challenge 写入统一使用的 Identity 事务锁。 */
    void lockIdentity(UUID identityId);

    PasswordCredential create(PasswordCredential credential);

    /** 锁定 Identity 后，仅在从未存在任何 Credential 时创建首个正式密码。 */
    Optional<PasswordCredential> createFirstPassword(PasswordCredential credential);

    /**
     * 原子地创建常规密码凭据，并永久失效且保留指定的初始密码凭据。
     */
    PasswordCredential replaceInitialPassword(PasswordCredential initialCredential, PasswordCredential passwordCredential);

    void invalidate(UUID credentialId, Instant invalidatedAt);

    /** 锁定该 Identity 的全部凭据，供凭据替换事务重新检查正式密码状态。 */
    List<PasswordCredential> lockCredentials(UUID identityId);

    List<PasswordCredential> findCredentials(UUID identityId);

    Optional<PasswordCredential> findCredential(UUID credentialId);
}
