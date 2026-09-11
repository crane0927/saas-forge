package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record QuotaDefinitionRecoveryRow(UUID id, UUID actor, UUID key, String operation,
        UUID targetId, OffsetDateTime createdAt, OffsetDateTime replayUntil, String responseBody) { }
