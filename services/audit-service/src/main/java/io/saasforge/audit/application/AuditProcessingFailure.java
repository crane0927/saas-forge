package io.saasforge.audit.application;

import java.util.UUID;

public record AuditProcessingFailure(
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
        int attemptCount) {}
