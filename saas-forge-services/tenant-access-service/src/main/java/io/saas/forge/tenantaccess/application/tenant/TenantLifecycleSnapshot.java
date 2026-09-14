package io.saas.forge.tenantaccess.application.tenant;

import io.saas.forge.tenantaccess.domain.tenant.Tenant;
import java.util.Optional;

/** 同一 Tenant 状态下读取的生命周期操作。 */
public record TenantLifecycleSnapshot(Tenant tenant, Optional<TenantLifecycleWorkflow> workflow) {
}
