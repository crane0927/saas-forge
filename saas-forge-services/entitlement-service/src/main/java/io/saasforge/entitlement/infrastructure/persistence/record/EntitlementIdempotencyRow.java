package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EntitlementIdempotencyRow(
        UUID callerIdentityId,
        UUID idempotencyKey,
        String operationType,
        String requestFingerprint,
        UUID targetId,
        Integer responseStatus,
        String responseKind,
        String responseBody,
        OffsetDateTime completedAt,
        OffsetDateTime expiresAt) {
}
