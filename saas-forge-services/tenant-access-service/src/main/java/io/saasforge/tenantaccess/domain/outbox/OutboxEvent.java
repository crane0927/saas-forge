package io.saasforge.tenantaccess.domain.outbox;

import java.time.Instant;
import java.util.UUID;

/** 与 Tenant 领域变更在同一事务固化的 CloudEvents JSON 快照。 */
public record OutboxEvent(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        String topic,
        String orderingKey,
        String traceId,
        String eventSnapshot) {

    public OutboxEvent {
        if (eventId == null || eventId.version() != 7 || tenantId == null || tenantId.version() != 7
                || occurredAt == null || topic == null || topic.isBlank()
                || orderingKey == null || orderingKey.isBlank()
                || eventSnapshot == null || eventSnapshot.isBlank()) {
            throw new IllegalArgumentException("Outbox Event 必要字段不合法");
        }
        if (traceId != null && !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("traceId 必须是非零小写 W3C Trace ID");
        }
    }
}
