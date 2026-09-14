package io.saasforge.entitlement.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record QuotaDefinitionRow(
        UUID id, String code, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
