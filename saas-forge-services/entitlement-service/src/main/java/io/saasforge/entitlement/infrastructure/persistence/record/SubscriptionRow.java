package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubscriptionRow(
        UUID id,
        UUID tenantId,
        UUID planId,
        String status,
        OffsetDateTime endsAt,
        OffsetDateTime createdAt) {
}
