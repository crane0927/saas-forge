package io.saasforge.iam.domain.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RevocationFenceRepository {
    /** Tenant 锁先于 Membership 锁；建 Fence 与 Token 提交共用该顺序。 */
    void lock(RevocationFenceTarget target);

    Optional<RevocationFence> findByRequestId(UUID revocationRequestId);

    Optional<RevocationFence> findActiveTenant(UUID tenantId);

    Optional<RevocationFence> findActiveMembership(UUID membershipId);

    RevocationFence create(RevocationFence fence);

    List<RevocationFence> findActive();
}
