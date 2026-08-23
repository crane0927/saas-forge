package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.domain.tenant.Tenant;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantCreationMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTenantRepository implements TenantRepository {
    private final TenantCreationMapper mapper;

    public MyBatisTenantRepository(TenantCreationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void setOperationTarget(UUID tenantId) {
        if (!tenantId.toString().equals(mapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Tenant Operation Target 设置失败");
        }
    }

    @Override
    public void create(Tenant tenant) {
        TenantRow row = new TenantRow(
                tenant.id(), tenant.displayName(), tenant.status().name(),
                TenantAccessTime.asOffsetDateTime(tenant.expiresAt()),
                TenantAccessTime.asOffsetDateTime(tenant.createdAt()),
                TenantAccessTime.asOffsetDateTime(tenant.updatedAt()));
        if (mapper.insertTenant(row) != 1) {
            throw new IllegalStateException("Tenant 保存失败");
        }
    }
}
