package io.saasforge.tenantaccess.infrastructure.persistence.mapper;

import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.util.List;
import java.util.UUID;

public interface TenantQueryMapper {
    List<TenantRow> list(Query query);
    record Query(String name, String status, UUID after, int limit) { }
}
