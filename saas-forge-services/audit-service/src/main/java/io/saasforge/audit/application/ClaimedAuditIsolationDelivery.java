package io.saasforge.audit.application;

import java.util.UUID;

public record ClaimedAuditIsolationDelivery(
        UUID deliveryId,
        UUID isolationId,
        String consumerName,
        String topic,
        String orderingKey,
        UUID eventId,
        String eventSnapshot,
        String claimant,
        int attemptCount) {}
