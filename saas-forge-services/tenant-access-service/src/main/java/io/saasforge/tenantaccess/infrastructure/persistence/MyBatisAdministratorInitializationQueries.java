package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.administrator.AdministratorInitializationQueries;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleException;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantAdministratorInitializationMapper;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisAdministratorInitializationQueries implements AdministratorInitializationQueries {
    private final TenantAdministratorInitializationMapper mapper;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final TenantRepository tenants;

    public MyBatisAdministratorInitializationQueries(TenantAdministratorInitializationMapper mapper,
            tools.jackson.databind.ObjectMapper objectMapper, TenantRepository tenants) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.tenants = tenants;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot get(UUID tenantId) {
        tenants.setOperationTarget(tenantId);
        var tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new TenantLifecycleException("TENANT_NOT_FOUND", "Tenant not found"));
        var row = mapper.findAuthoritativeWorkflow(tenantId);
        return new Snapshot(tenant, row == null ? null : MyBatisTenantAdministratorInitializationRepository.fromRow(row, objectMapper),
                mapper.findInitialAdministratorMembershipId(tenantId));
    }
}
