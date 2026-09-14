package io.saasforge.audit.application;

import java.util.UUID;

public record AuditConsumerIsolation(
        String consumerName,
        String topic,
        int partition,
        long offset,
        String orderingKey,
        UUID eventId,
        String source,
        String sourceType,
        String payloadSha256,
        String failureCategory,
        String diagnostic,
        int attemptCount,
        String safeSnapshot,
        String isolationTopic) {
    public AuditConsumerIsolation {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("Audit Consumer 隔离尝试次数必须为正数");
        }
        if ((safeSnapshot == null) != (isolationTopic == null)) {
            throw new IllegalArgumentException("安全快照与隔离 Topic 必须同时存在或同时为空");
        }
        if (safeSnapshot != null && eventId == null) {
            throw new IllegalArgumentException("可重放安全快照必须保留原 Event ID");
        }
    }
}
