package io.saasforge.entitlement.domain.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID eventId,
        UUID aggregateId,
        Instant occurredAt,
        String topic,
        String orderingKey,
        String traceId,
        String eventSnapshot) {
}
