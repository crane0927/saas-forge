package io.saas.forge.tenantaccess.domain.outbox;

import java.util.UUID;

public record ClaimedOutboxEvent(
        UUID eventId,
        UUID tenantId,
        String topic,
        String orderingKey,
        String eventSnapshot,
        String claimant,
        int attemptCount) {
}
