package io.saasforge.entitlement.infrastructure.persistence.record;

import java.util.UUID;

public record PlanQuotaLimitRow(UUID planId, UUID quotaDefinitionId, int quotaLimit) {
}
