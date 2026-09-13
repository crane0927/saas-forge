package io.saasforge.entitlement.infrastructure.persistence.mapper;

import io.saasforge.entitlement.infrastructure.persistence.record.PlanRecoveryRow;
import java.util.List;
import java.util.UUID;

public interface PlanRecoveryMapper {
    int prepare(PlanRecoveryRow row);
    PlanRecoveryRow findByKey(ActorKey query);
    PlanRecoveryRow find(ActorId query);
    boolean tryLock(UUID id);
    boolean canExecute(UUID id);
    int complete(PlanRecoveryRow row);
    List<UUID> list(Query query);
    record ActorKey(UUID actor, UUID key) { }
    record ActorId(UUID actor, UUID id) { }
    record Query(UUID actor, UUID after, int limit) { }
}
