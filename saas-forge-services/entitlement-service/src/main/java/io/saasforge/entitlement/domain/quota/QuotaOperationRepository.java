package io.saasforge.entitlement.domain.quota;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Quota Operation、Usage 与当前 Subscription Limit 的 PostgreSQL 一致性边界。 */
public interface QuotaOperationRepository {
    void setOperationTarget(UUID tenantId);

    boolean claim(QuotaOperation operation);

    Optional<QuotaOperation> find(UUID operationId);

    Optional<UUID> findQuotaDefinitionId(String quotaCode);

    Optional<Integer> findCurrentLimit(UUID tenantId, UUID quotaDefinitionId, Instant at);

    Optional<Integer> findGrantedLimit(UUID tenantId, UUID quotaDefinitionId);

    void initializeUsage(UUID tenantId, UUID quotaDefinitionId, Instant at);

    Optional<Integer> consume(UUID tenantId, UUID quotaDefinitionId, int amount, int limit, Instant at);

    Optional<Integer> release(UUID tenantId, UUID quotaDefinitionId, int amount, Instant at);

    int currentUsage(UUID tenantId, UUID quotaDefinitionId);

    void complete(UUID operationId, QuotaOperationOutcome outcome, Integer usage, Integer limit, Instant at);
}
