package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.domain.outbox.OutboxEvent;
import io.saasforge.entitlement.domain.outbox.OutboxEventRepository;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.EntitlementOutboxRow;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisEntitlementOutboxEventRepository implements OutboxEventRepository {
    private final EntitlementBootstrapMapper mapper;

    public MyBatisEntitlementOutboxEventRepository(EntitlementBootstrapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(OutboxEvent event) {
        EntitlementOutboxRow row = new EntitlementOutboxRow(
                event.eventId(), event.aggregateId(), EntitlementTime.asOffsetDateTime(event.occurredAt()),
                event.topic(), event.orderingKey(), event.traceId(), event.eventSnapshot());
        if (mapper.insertOutbox(row) != 1) {
            throw new IllegalStateException("Entitlement Outbox Event 保存失败");
        }
    }
}
