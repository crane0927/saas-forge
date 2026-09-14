package io.saasforge.iam.domain.session;

import java.time.Instant;
import java.util.UUID;

/** PostgreSQL 权威 Fence generation；原撤销请求 ID 不可与目标重新绑定。 */
public record RevocationFence(
        UUID revocationRequestId,
        RevocationFenceTarget target,
        RevocationFenceStatus status,
        Instant establishedAt,
        Instant releasedAt) {

    public RevocationFence {
        if (revocationRequestId == null || revocationRequestId.version() != 7
                || target == null || status == null || establishedAt == null) {
            throw new IllegalArgumentException("Revocation Fence 参数不完整");
        }
        if ((status == RevocationFenceStatus.ACTIVE) != (releasedAt == null)
                || (releasedAt != null && releasedAt.isBefore(establishedAt))) {
            throw new IllegalArgumentException("Revocation Fence 状态与时间不一致");
        }
    }

    public static RevocationFence establish(
            UUID revocationRequestId, RevocationFenceTarget target, Instant establishedAt) {
        return new RevocationFence(
                revocationRequestId, target, RevocationFenceStatus.ACTIVE, establishedAt, null);
    }
}
