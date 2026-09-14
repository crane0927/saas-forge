package io.saasforge.tenantaccess.application.tenant;

public enum TenantLifecycleStatus {
    PENDING,
    COMPLETED,
    RETRY_REQUIRED,
    RECOVERY_REQUIRED
}
