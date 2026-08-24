package io.saasforge.tenantaccess.domain.tenant;

import java.time.Instant;
import java.util.UUID;

/** Tenant Access 拥有的 Tenant 生命周期聚合根。 */
public record Tenant(
        UUID id,
        String displayName,
        TenantStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public Tenant {
        if (id == null || id.version() != 7 || displayName == null || displayName.isBlank()
                || displayName.length() > 200 || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Tenant 必要字段不合法");
        }
    }

    public static Tenant pending(UUID id, String displayName, Instant expiresAt, Instant now) {
        if (now == null || (expiresAt != null && !expiresAt.isAfter(now))) {
            throw new TenantExpiryInvalidException();
        }
        return new Tenant(id, displayName, TenantStatus.PENDING, expiresAt, now, now);
    }
}
