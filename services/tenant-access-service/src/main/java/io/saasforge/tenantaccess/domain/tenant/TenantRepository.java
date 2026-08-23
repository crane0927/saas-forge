package io.saasforge.tenantaccess.domain.tenant;

import java.util.UUID;
import java.util.Optional;

public interface TenantRepository {
    void setOperationTarget(UUID tenantId);

    void create(Tenant tenant);

    Optional<Tenant> findById(UUID tenantId);
}
