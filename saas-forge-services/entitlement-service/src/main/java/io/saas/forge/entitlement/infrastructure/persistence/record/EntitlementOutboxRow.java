package io.saas.forge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EntitlementOutboxRow(
        UUID eventId,
        UUID aggregateId,
        OffsetDateTime occurredAt,
        String topic,
        String orderingKey,
        String traceId,
        String eventSnapshot) {
}
