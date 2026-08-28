package io.saasforge.iam.domain.client;

import java.util.Optional;
import java.util.UUID;

public interface OAuthClientManagementOperationRepository {
    boolean tryLock(UUID actorIdentityId, UUID idempotencyKey);

    Optional<OAuthClientManagementOperation> find(UUID actorIdentityId, UUID idempotencyKey);

    void append(OAuthClientManagementOperation operation);
}
