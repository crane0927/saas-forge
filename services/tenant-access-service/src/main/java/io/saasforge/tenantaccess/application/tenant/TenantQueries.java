package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.tenant.TenantStatus;

public interface TenantQueries {
    TenantQueryService.Page list(String name, TenantStatus status, String cursor, int limit);
}
