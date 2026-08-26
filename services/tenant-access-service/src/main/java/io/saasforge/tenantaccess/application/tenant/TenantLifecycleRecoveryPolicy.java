package io.saasforge.tenantaccess.application.tenant;

import java.time.Duration;

public record TenantLifecycleRecoveryPolicy(
        Duration leaseDuration,
        Duration retryDelay,
        int maximumAttempts) {
    public TenantLifecycleRecoveryPolicy {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()
                || retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()
                || maximumAttempts < 1) {
            throw new IllegalArgumentException("Tenant Lifecycle 恢复策略不合法");
        }
    }
}
