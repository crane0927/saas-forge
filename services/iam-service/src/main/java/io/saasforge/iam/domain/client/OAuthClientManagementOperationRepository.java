package io.saasforge.iam.domain.client;

import java.util.Optional;
import java.util.UUID;

public interface OAuthClientManagementOperationRepository {
    boolean tryLock(UUID actorIdentityId, UUID idempotencyKey);

    Optional<OAuthClientManagementOperation> find(UUID actorIdentityId, UUID idempotencyKey);

    /** 以原操作锁串行化不同恢复幂等键对同一个一次性恢复资格的竞争。 */
    boolean tryLockRecovery(UUID originalOperationId);

    Optional<OAuthClientManagementOperation> findSuccessfulRecovery(UUID originalOperationId);

    void append(OAuthClientManagementOperation operation);
}
