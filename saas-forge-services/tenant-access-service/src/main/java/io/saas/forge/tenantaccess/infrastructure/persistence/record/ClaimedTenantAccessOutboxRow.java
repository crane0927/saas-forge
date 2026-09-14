package io.saas.forge.tenantaccess.infrastructure.persistence.record;

import java.util.UUID;

public record ClaimedTenantAccessOutboxRow(
        UUID eventId,
        UUID tenantId,
        String topic,
        String orderingKey,
        String eventSnapshot,
        String claimedBy,
        int attemptCount) {
}
