package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public class OutboxEventRow {
    private UUID eventId;
    private OffsetDateTime occurredAt;
    private String topic;
    private String orderingKey;
    private String traceId;
    private String eventSnapshot;
    private String claimedBy;
    private Integer attemptCount;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getOrderingKey() { return orderingKey; }
    public void setOrderingKey(String orderingKey) { this.orderingKey = orderingKey; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getEventSnapshot() { return eventSnapshot; }
    public void setEventSnapshot(String eventSnapshot) { this.eventSnapshot = eventSnapshot; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
}
