package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantCreationRecoveryRow(UUID id, UUID actor, UUID key, String displayName,
        String tenantExpiresAt, OffsetDateTime createdAt, OffsetDateTime replayUntil, String responseBody) { }
