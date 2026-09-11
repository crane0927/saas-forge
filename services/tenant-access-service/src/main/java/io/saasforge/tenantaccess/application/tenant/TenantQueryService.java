package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.tenant.Tenant;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantQueryService {
    private final TenantQueries queries;
    private final TenantRepository tenants;

    public TenantQueryService(TenantQueries queries, TenantRepository tenants) {
        this.queries = queries;
        this.tenants = tenants;
    }

    public Page list(String name, TenantStatus status, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (name != null && name.length() > 200)) {
            throw new IllegalArgumentException("Invalid Tenant filter or page size");
        }
        return queries.list(name == null ? "" : name, status, cursor, limit);
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID tenantId) {
        tenants.setOperationTarget(tenantId);
        return tenants.findById(tenantId)
                .orElseThrow(() -> new TenantLifecycleException("TENANT_NOT_FOUND", "Tenant not found"));
    }

    public record Page(List<Tenant> items, String nextCursor, boolean hasMore) { }
}
