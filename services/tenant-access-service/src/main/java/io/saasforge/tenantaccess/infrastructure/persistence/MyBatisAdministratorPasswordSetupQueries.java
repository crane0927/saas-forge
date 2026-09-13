package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.administrator.AdministratorInitializationQueries;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupQueries;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupWorkflow;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.AdministratorPasswordSetupMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisAdministratorPasswordSetupQueries implements AdministratorPasswordSetupQueries {
    private final AdministratorInitializationQueries initializations;
    private final AdministratorPasswordSetupMapper mapper;

    public MyBatisAdministratorPasswordSetupQueries(AdministratorInitializationQueries initializations,
            AdministratorPasswordSetupMapper mapper) {
        this.initializations = initializations;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot get(UUID actor, UUID tenantId, UUID key, UUID resendId, Instant now) {
        var initialization = initializations.get(tenantId);
        var row = mapper.findLatest(tenantId);
        // 公共最新投递与私有恢复独立查询，不能让另一操作者的新记录遮蔽原未决请求。
        var selected = key != null ? mapper.findByKey(actor, tenantId, key, TenantAccessTime.asOffsetDateTime(now))
                : resendId != null ? mapper.findRecoverable(actor, tenantId, resendId, TenantAccessTime.asOffsetDateTime(now))
                : mapper.findPendingActor(actor, tenantId);
        return new Snapshot(initialization, mapper.findInitialAdministratorIdentityId(tenantId),
                row == null ? null : MyBatisAdministratorPasswordSetupRepository.fromRow(row),
                selected == null ? null : MyBatisAdministratorPasswordSetupRepository.fromRow(selected), mapper.hasPending(tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdministratorPasswordSetupWorkflow> findRecoverable(UUID actor, UUID tenantId, UUID resendId, Instant now) {
        mapper.setOperationTarget(tenantId);
        return Optional.ofNullable(mapper.findRecoverable(actor, tenantId, resendId, TenantAccessTime.asOffsetDateTime(now)))
                .map(MyBatisAdministratorPasswordSetupRepository::fromRow);
    }
}
