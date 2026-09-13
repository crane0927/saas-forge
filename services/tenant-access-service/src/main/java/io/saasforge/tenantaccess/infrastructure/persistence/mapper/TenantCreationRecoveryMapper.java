package io.saasforge.tenantaccess.infrastructure.persistence.mapper;

import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantCreationRecoveryRow;
import java.util.List;
import java.util.UUID;

public interface TenantCreationRecoveryMapper {
    int prepare(TenantCreationRecoveryRow row);
    TenantCreationRecoveryRow findByKey(ActorKey query);
    TenantCreationRecoveryRow find(ActorId query);
    boolean tryLock(UUID id);
    int complete(TenantCreationRecoveryRow row);
    List<UUID> list(Query query);
    record ActorKey(UUID actor, UUID key) { }
    record ActorId(UUID actor, UUID id) { }
    record Query(UUID actor, UUID after, int limit) { }
}
