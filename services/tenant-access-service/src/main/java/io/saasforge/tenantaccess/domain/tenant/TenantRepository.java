package io.saasforge.tenantaccess.domain.tenant;

import java.util.UUID;

public interface TenantRepository {
    void setOperationTarget(UUID tenantId);

    void create(Tenant tenant);
}
