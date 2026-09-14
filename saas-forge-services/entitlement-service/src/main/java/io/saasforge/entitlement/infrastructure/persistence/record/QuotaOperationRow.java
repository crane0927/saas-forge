package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record QuotaOperationRow(
        UUID operationId,
        UUID callerClientId,
        UUID tenantId,
        String quotaCode,
        int amount,
        String action,
        String purpose,
        String requestFingerprint,
        String outcome,
        Integer usage,
        Integer limit,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {
}
