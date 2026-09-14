package io.saasforge.sdk.tenant;

import java.util.UUID;

/** 当前请求中已经验证的原子 Tenant Context，不携带 Token 或原始 Claim。 */
public record TenantContextSnapshot(UUID identityId, UUID membershipId, UUID tenantId) {

    public TenantContextSnapshot {
        if (identityId == null || membershipId == null || tenantId == null) {
            throw new IllegalArgumentException("Tenant Context 字段不完整");
        }
    }
}
