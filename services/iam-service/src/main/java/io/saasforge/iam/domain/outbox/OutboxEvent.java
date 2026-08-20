package io.saasforge.iam.domain.outbox;

import java.time.Instant;
import java.util.UUID;

/** 事务内固化的完整 CloudEvents JSON 快照。 */
public record OutboxEvent(
        UUID eventId,
        Instant occurredAt,
        String topic,
        String orderingKey,
        String traceId,
        String eventSnapshot) {

    public OutboxEvent {
        if (eventId == null || eventId.version() != 7 || occurredAt == null
                || topic == null || topic.isBlank() || orderingKey == null || orderingKey.isBlank()
                || eventSnapshot == null || eventSnapshot.isBlank()) {
            throw new IllegalArgumentException("Outbox Event 必要字段不合法");
        }
        if (traceId != null && !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("traceId 必须是非零小写 W3C Trace ID");
        }
    }
}
