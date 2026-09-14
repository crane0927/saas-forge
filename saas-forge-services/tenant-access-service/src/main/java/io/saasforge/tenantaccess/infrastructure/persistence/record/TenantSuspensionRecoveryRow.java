package io.saasforge.tenantaccess.infrastructure.persistence.record;

import java.util.UUID;

public record TenantSuspensionRecoveryRow(UUID workflowId, String requestFingerprint) {
}
