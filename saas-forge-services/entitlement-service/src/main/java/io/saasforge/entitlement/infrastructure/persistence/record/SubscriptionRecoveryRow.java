package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubscriptionRecoveryRow(UUID id, UUID actor, UUID key, UUID tenantId, UUID planId,
        String endsAt, OffsetDateTime createdAt, OffsetDateTime replayUntil, String responseBody) { }
