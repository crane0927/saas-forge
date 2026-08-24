package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.domain.quota.QuotaOperation;
import io.saasforge.entitlement.domain.quota.QuotaOperationAction;
import io.saasforge.entitlement.domain.quota.QuotaOperationOutcome;
import io.saasforge.entitlement.domain.quota.QuotaOperationPurpose;
import io.saasforge.entitlement.domain.quota.QuotaOperationRepository;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.QuotaOperationRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisQuotaOperationRepository implements QuotaOperationRepository {
    private final EntitlementBootstrapMapper mapper;

    public MyBatisQuotaOperationRepository(EntitlementBootstrapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void setOperationTarget(UUID tenantId) {
        if (!tenantId.toString().equals(mapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Entitlement Tenant Operation Target 设置失败");
        }
    }

    @Override
    public boolean claim(QuotaOperation operation) {
        return mapper.claimQuotaOperation(toRow(operation)) == 1;
    }

    @Override
    public Optional<QuotaOperation> find(UUID operationId) {
        return Optional.ofNullable(mapper.findQuotaOperation(operationId)).map(MyBatisQuotaOperationRepository::toDomain);
    }

    @Override
    public Optional<UUID> findQuotaDefinitionId(String quotaCode) {
        return Optional.ofNullable(mapper.findQuotaDefinitionIdByCode(quotaCode));
    }

    @Override
    public Optional<Integer> findCurrentLimit(UUID tenantId, UUID quotaDefinitionId, Instant at) {
        return Optional.ofNullable(mapper.findCurrentQuotaLimit(
                tenantId, quotaDefinitionId, EntitlementTime.asOffsetDateTime(at)));
    }

    @Override
    public Optional<Integer> findGrantedLimit(UUID tenantId, UUID quotaDefinitionId) {
        return Optional.ofNullable(mapper.findGrantedQuotaLimit(tenantId, quotaDefinitionId));
    }

    @Override
    public void initializeUsage(UUID tenantId, UUID quotaDefinitionId, Instant at) {
        mapper.initializeQuotaUsage(tenantId, quotaDefinitionId, EntitlementTime.asOffsetDateTime(at));
    }

    @Override
    public Optional<Integer> consume(
            UUID tenantId, UUID quotaDefinitionId, int amount, int limit, Instant at) {
        return Optional.ofNullable(mapper.consumeQuota(
                tenantId, quotaDefinitionId, amount, limit, EntitlementTime.asOffsetDateTime(at)));
    }

    @Override
    public Optional<Integer> release(
            UUID tenantId, UUID quotaDefinitionId, int amount, Instant at) {
        return Optional.ofNullable(mapper.releaseQuota(
                tenantId, quotaDefinitionId, amount, EntitlementTime.asOffsetDateTime(at)));
    }

    @Override
    public int currentUsage(UUID tenantId, UUID quotaDefinitionId) {
        return mapper.findCurrentUsage(tenantId, quotaDefinitionId);
    }

    @Override
    public void complete(
            UUID operationId,
            QuotaOperationOutcome outcome,
            Integer usage,
            Integer limit,
            Instant at) {
        if (mapper.completeQuotaOperation(
                operationId, outcome.name(), usage, limit, EntitlementTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Quota Operation 完成结果保存失败");
        }
    }

    private static QuotaOperationRow toRow(QuotaOperation operation) {
        return new QuotaOperationRow(
                operation.operationId(), operation.callerClientId(), operation.tenantId(), operation.quotaCode(),
                operation.amount(), operation.action().name(), operation.purpose().name(),
                operation.requestFingerprint(), operation.outcome() == null ? null : operation.outcome().name(),
                operation.usage(), operation.limit(), EntitlementTime.asOffsetDateTime(operation.createdAt()),
                EntitlementTime.asOffsetDateTime(operation.completedAt()));
    }

    private static QuotaOperation toDomain(QuotaOperationRow row) {
        return new QuotaOperation(
                row.operationId(), row.callerClientId(), row.tenantId(), row.quotaCode(), row.amount(),
                QuotaOperationAction.valueOf(row.action()), QuotaOperationPurpose.valueOf(row.purpose()),
                row.requestFingerprint(), row.outcome() == null ? null : QuotaOperationOutcome.valueOf(row.outcome()),
                row.usage(), row.limit(), EntitlementTime.asInstant(row.createdAt()),
                EntitlementTime.asInstant(row.completedAt()));
    }
}
