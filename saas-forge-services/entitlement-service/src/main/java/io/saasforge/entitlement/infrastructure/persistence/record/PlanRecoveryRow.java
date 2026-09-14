package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlanRecoveryRow(UUID id, UUID actor, UUID key, String operation,
        UUID targetId, OffsetDateTime createdAt, OffsetDateTime replayUntil, String responseBody, String requestBody) { }
