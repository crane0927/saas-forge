package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.tenant.TenantStatus;

public interface TenantQueries {
    TenantQueryService.Page list(String name, TenantStatus status, String cursor, int limit);
}
