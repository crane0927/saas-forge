package io.saasforge.tenantaccess.application.administrator;

import io.saasforge.tenantaccess.domain.tenant.Tenant;
import java.util.UUID;

public interface AdministratorInitializationQueries {
    /** 同一数据库快照读取根工作流和不可变初始 Membership，不读取 IAM 或 Entitlement 数据。 */
    Snapshot get(UUID tenantId);
    record Snapshot(Tenant tenant, InitializationWorkflow workflow, UUID initialMembershipId) { }
}
