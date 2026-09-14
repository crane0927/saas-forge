package io.saas.forge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantCreationIdempotencyRow(
        UUID callerIdentityId,
        UUID idempotencyKey,
        String requestFingerprint,
        UUID tenantId,
        Integer responseStatus,
        String responseBody,
        OffsetDateTime completedAt,
        OffsetDateTime expiresAt) {
}
