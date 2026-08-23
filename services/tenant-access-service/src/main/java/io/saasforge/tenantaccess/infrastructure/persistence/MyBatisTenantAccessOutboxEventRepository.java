package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.domain.outbox.OutboxEvent;
import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantCreationMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantAccessOutboxRow;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTenantAccessOutboxEventRepository implements OutboxEventRepository {
    private final TenantCreationMapper mapper;

    public MyBatisTenantAccessOutboxEventRepository(TenantCreationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(OutboxEvent event) {
        TenantAccessOutboxRow row = new TenantAccessOutboxRow(
                event.eventId(), event.tenantId(), TenantAccessTime.asOffsetDateTime(event.occurredAt()),
                event.topic(), event.orderingKey(), event.traceId(), event.eventSnapshot());
        if (mapper.insertOutbox(row) != 1) {
            throw new IllegalStateException("Tenant Access Outbox Event 保存失败");
        }
    }
}
