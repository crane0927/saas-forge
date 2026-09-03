package io.saasforge.iam.application.authentication;

import java.util.List;
import java.util.Objects;

/** Tenant Access 权威查询在 Access Token 签发时形成的当前 Tenant Context 只读快照。 */
public record TenantAuthenticationContextSnapshot(
        AccessibleMembership currentMembership,
        List<AccessibleMembership> accessibleMemberships) {

    public TenantAuthenticationContextSnapshot {
        Objects.requireNonNull(currentMembership, "当前 Membership 不能为空");
        accessibleMemberships = List.copyOf(accessibleMemberships);
        if (accessibleMemberships.isEmpty()
                || accessibleMemberships.stream().noneMatch(candidate -> candidate.equals(currentMembership))) {
            throw new IllegalArgumentException("Accessible Memberships 必须包含当前 Membership");
        }
    }
}
