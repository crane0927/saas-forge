package io.saas.forge.tenantaccess.infrastructure.persistence.record;

import java.util.UUID;

public record TenantSuspensionRecoveryRow(UUID workflowId, String requestFingerprint) {
}
