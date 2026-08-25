package io.saasforge.iam.domain.session;

import java.util.UUID;

/** 强类型 Fence 目标；Membership 目标必须同时绑定所属 Tenant。 */
public record RevocationFenceTarget(
        RevocationFenceTargetType type,
        UUID membershipId,
        UUID tenantId) {

    public RevocationFenceTarget {
        if (type == null || tenantId == null || tenantId.version() != 7) {
            throw new IllegalArgumentException("Revocation Fence Tenant 目标必须是 UUIDv7");
        }
        if (type == RevocationFenceTargetType.TENANT && membershipId != null) {
            throw new IllegalArgumentException("Tenant Fence 不得绑定 Membership");
        }
        if (type == RevocationFenceTargetType.MEMBERSHIP
                && (membershipId == null || membershipId.version() != 7)) {
            throw new IllegalArgumentException("Membership Fence 必须绑定 UUIDv7 Membership 与 Tenant");
        }
    }

    public static RevocationFenceTarget membership(UUID membershipId, UUID tenantId) {
        return new RevocationFenceTarget(RevocationFenceTargetType.MEMBERSHIP, membershipId, tenantId);
    }

    public static RevocationFenceTarget tenant(UUID tenantId) {
        return new RevocationFenceTarget(RevocationFenceTargetType.TENANT, null, tenantId);
    }

    public UUID targetId() {
        return type == RevocationFenceTargetType.TENANT ? tenantId : membershipId;
    }
}
