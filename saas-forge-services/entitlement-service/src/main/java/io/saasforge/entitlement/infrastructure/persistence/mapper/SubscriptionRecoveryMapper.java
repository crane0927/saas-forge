package io.saasforge.entitlement.infrastructure.persistence.mapper;

import io.saasforge.entitlement.infrastructure.persistence.record.SubscriptionRecoveryRow;
import java.util.List;
import java.util.UUID;

public interface SubscriptionRecoveryMapper {
    int prepare(SubscriptionRecoveryRow row);
    SubscriptionRecoveryRow findByKey(ActorKey query);
    SubscriptionRecoveryRow find(ActorId query);
    boolean tryLock(UUID id);
    String setTenant(UUID tenantId);
    boolean canExecute(UUID id);
    int complete(SubscriptionRecoveryRow row);
    List<UUID> list(Query query);
    record ActorKey(UUID actor, UUID key) { }
    record ActorId(UUID actor, UUID id) { }
    record Query(UUID actor, UUID tenantId, UUID after, int limit) { }
}
