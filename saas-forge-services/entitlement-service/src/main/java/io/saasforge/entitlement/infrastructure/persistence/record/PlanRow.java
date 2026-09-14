package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlanRow(
        UUID id, String code, String displayName, String status,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
