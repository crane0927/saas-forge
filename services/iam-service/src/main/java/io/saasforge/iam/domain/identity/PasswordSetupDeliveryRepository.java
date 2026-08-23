package io.saasforge.iam.domain.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Password Setup 投递请求的幂等、尝试替换与成功确认边界。 */
public interface PasswordSetupDeliveryRepository {

    void lockRequest(UUID callerClientId, UUID requestId);

    Optional<PasswordSetupDelivery> find(UUID callerClientId, UUID requestId);

    void savePasswordReady(UUID callerClientId, UUID requestId, UUID identityId, Instant completedAt);

    boolean markPasswordReady(UUID callerClientId, UUID requestId, UUID identityId, Instant completedAt);

    void savePending(
            UUID callerClientId,
            UUID requestId,
            UUID identityId,
            UUID challengeId,
            Instant challengeExpiresAt);

    boolean markDelivered(
            UUID callerClientId,
            UUID requestId,
            UUID challengeId,
            Instant deliveredAt);
}
