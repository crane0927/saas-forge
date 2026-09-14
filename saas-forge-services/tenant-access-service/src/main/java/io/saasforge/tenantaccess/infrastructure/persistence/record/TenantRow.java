package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantRow(
        UUID id,
        String displayName,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
