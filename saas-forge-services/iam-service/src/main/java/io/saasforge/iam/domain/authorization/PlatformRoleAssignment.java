package io.saasforge.iam.domain.authorization;

import java.time.Instant;
import java.util.UUID;

/** IAM 持有的平台角色授予事实；Tenant Role 不属于该边界。 */
public record PlatformRoleAssignment(
        UUID id,
        UUID identityId,
        String roleKey,
        Instant assignedAt,
        Instant revokedAt) {

    public PlatformRoleAssignment {
        if (identityId == null || roleKey == null || roleKey.isBlank() || assignedAt == null) {
            throw new IllegalArgumentException("Platform Role Assignment 必要字段不能为空");
        }
        if (revokedAt != null && revokedAt.isBefore(assignedAt)) {
            throw new IllegalArgumentException("Platform Role 撤销时间不能早于授予时间");
        }
    }

    public static PlatformRoleAssignment grant(UUID identityId, String roleKey, Instant assignedAt) {
        return new PlatformRoleAssignment(null, identityId, roleKey, assignedAt, null);
    }

    public boolean isActiveAt(Instant at) {
        return !assignedAt.isAfter(at) && (revokedAt == null || revokedAt.isAfter(at));
    }
}
