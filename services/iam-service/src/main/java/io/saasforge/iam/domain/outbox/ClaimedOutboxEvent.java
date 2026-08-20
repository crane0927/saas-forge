package io.saasforge.iam.domain.outbox;

import java.util.UUID;

public record ClaimedOutboxEvent(
        UUID eventId,
        String topic,
        String orderingKey,
        String eventSnapshot,
        String claimant,
        int attemptCount) {
}
