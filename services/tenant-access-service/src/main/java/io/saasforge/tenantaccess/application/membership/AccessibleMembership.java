package io.saasforge.tenantaccess.application.membership;

import java.util.Objects;
import java.util.UUID;

public record AccessibleMembership(
        UUID membershipId,
        UUID tenantId,
        String tenantDisplayName,
        TenantBrandProfile brandProfile) {

    public AccessibleMembership(UUID membershipId, UUID tenantId, String tenantDisplayName) {
        this(membershipId, tenantId, tenantDisplayName, null);
    }

    public AccessibleMembership {
        Objects.requireNonNull(membershipId, "Membership ID 不能为空");
        Objects.requireNonNull(tenantId, "Tenant ID 不能为空");
        if (tenantDisplayName == null || tenantDisplayName.isBlank()) {
            throw new IllegalArgumentException("Tenant Display Name 不能为空");
        }
    }
}
