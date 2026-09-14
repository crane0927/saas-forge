package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.tenant.TenantQueries;
import io.saasforge.tenantaccess.application.tenant.TenantQueryService;
import io.saasforge.tenantaccess.domain.tenant.Tenant;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantQueryMapper;
import java.time.Clock;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTenantQueries implements TenantQueries {
    private final TenantQueryMapper mapper;
    private final TenantListCursor cursors;

    public MyBatisTenantQueries(TenantQueryMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.cursors = new TenantListCursor(clock);
    }

    @Override
    public TenantQueryService.Page list(String name, TenantStatus status, String cursor, int limit) {
        String scope = "tenants:" + Base64.getUrlEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8))
                + ":" + status;
        var rows = mapper.list(new TenantQueryMapper.Query(name, status == null ? null : status.name(),
                cursors.decode(scope, cursor), limit + 1));
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(row -> new Tenant(
                row.id(), row.displayName(), TenantStatus.valueOf(row.status()),
                TenantAccessTime.asInstant(row.expiresAt()), TenantAccessTime.asInstant(row.createdAt()),
                TenantAccessTime.asInstant(row.updatedAt()))).toList();
        return new TenantQueryService.Page(items,
                more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }
}
