package io.saas.forge.tenantaccess.application.administrator;

import io.saas.forge.tenantaccess.domain.tenant.TenantStatus;
import java.time.Instant;
import java.util.UUID;

public record TenantAdministratorInitializationResult(
        UUID id,
        String displayName,
        TenantStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
