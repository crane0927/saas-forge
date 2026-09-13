package io.saasforge.entitlement.infrastructure.persistence.mapper;

import io.saasforge.entitlement.infrastructure.persistence.record.QuotaDefinitionRecoveryRow;
import java.util.List;
import java.util.UUID;

public interface QuotaDefinitionRecoveryMapper {
    int prepare(QuotaDefinitionRecoveryRow row);
    QuotaDefinitionRecoveryRow findByKey(ActorKey query);
    QuotaDefinitionRecoveryRow find(ActorId query);
    boolean tryLock(UUID id);
    boolean canExecute(UUID id);
    int complete(QuotaDefinitionRecoveryRow row);
    List<UUID> list(Query query);
    record ActorKey(UUID actor, UUID key) { }
    record ActorId(UUID actor, UUID id) { }
    record Query(UUID actor, UUID after, int limit) { }
}
