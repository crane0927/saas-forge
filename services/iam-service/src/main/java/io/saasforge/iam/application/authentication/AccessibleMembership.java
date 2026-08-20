package io.saasforge.iam.application.authentication;

import java.util.Objects;
import java.util.UUID;

public record AccessibleMembership(UUID membershipId, UUID tenantId, String tenantDisplayName) {

    public AccessibleMembership {
        Objects.requireNonNull(membershipId, "Membership ID 不能为空");
        Objects.requireNonNull(tenantId, "Tenant ID 不能为空");
        if (tenantDisplayName == null || tenantDisplayName.isBlank()) {
            throw new IllegalArgumentException("Tenant Display Name 不能为空");
        }
    }
}
