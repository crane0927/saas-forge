package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import java.time.Instant;
import java.util.UUID;

public record TenantCreationResult(
        UUID id,
        String displayName,
        TenantStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
