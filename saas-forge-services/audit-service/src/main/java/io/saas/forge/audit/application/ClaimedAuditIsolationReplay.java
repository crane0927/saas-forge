package io.saas.forge.audit.application;

import java.util.UUID;

public record ClaimedAuditIsolationReplay(
        UUID replayId,
        UUID isolationId,
        String topic,
        String orderingKey,
        UUID eventId,
        String eventSnapshot,
        String claimant,
        int attemptCount) {}
