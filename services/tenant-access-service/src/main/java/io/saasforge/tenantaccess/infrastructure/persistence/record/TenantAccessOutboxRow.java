package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantAccessOutboxRow(
        UUID eventId,
        UUID tenantId,
        OffsetDateTime occurredAt,
        String topic,
        String orderingKey,
        String traceId,
        String eventSnapshot) {
}
