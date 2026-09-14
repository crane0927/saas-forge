package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class InitialSubscriptionEligibilityService {
    private final TenantRepository tenants;
    private final Clock clock;

    public InitialSubscriptionEligibilityService(TenantRepository tenants, Clock clock) {
        this.tenants = tenants;
        this.clock = clock;
    }

    /** 资格只由调用时刻的 Tenant 权威状态和可选绝对 expiresAt 派生。 */
    @Transactional(readOnly = true)
    public InitialSubscriptionEligibility check(UUID tenantId) {
        requireUuidV7(tenantId);
        tenants.setOperationTarget(tenantId);
        return tenants.findById(tenantId)
                .map(tenant -> {
                    if (tenant.status() != TenantStatus.PENDING) {
                        return InitialSubscriptionEligibility.INVALID_STATE;
                    }
                    if (tenant.expiresAt() != null && !tenant.expiresAt().isAfter(clock.instant())) {
                        return InitialSubscriptionEligibility.EXPIRY_REACHED;
                    }
                    return InitialSubscriptionEligibility.PENDING_ELIGIBLE;
                })
                .orElse(InitialSubscriptionEligibility.NOT_FOUND);
    }

    private static void requireUuidV7(UUID tenantId) {
        if (tenantId == null || tenantId.version() != 7) {
            throw new IllegalArgumentException("Tenant ID 必须是 UUIDv7");
        }
    }
}
