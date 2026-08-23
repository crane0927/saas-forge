package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.tenantaccess.domain.outbox.OutboxEvent;
import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantAccessOutboxMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantCreationMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.ClaimedTenantAccessOutboxRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantAccessOutboxRow;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisTenantAccessOutboxEventRepository implements OutboxEventRepository {
    private final TenantCreationMapper mapper;
    private final TenantAccessOutboxMapper outboxMapper;

    public MyBatisTenantAccessOutboxEventRepository(
            TenantCreationMapper mapper, TenantAccessOutboxMapper outboxMapper) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
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

    @Override
    @Transactional
    public Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant claimedUntil) {
        var eventId = outboxMapper.claimNext(
                claimant, TenantAccessTime.asOffsetDateTime(at), TenantAccessTime.asOffsetDateTime(claimedUntil));
        if (eventId == null) {
            return Optional.empty();
        }
        ClaimedTenantAccessOutboxRow row = outboxMapper.findClaimed(eventId);
        return Optional.of(new ClaimedOutboxEvent(
                row.eventId(), row.tenantId(), row.topic(), row.orderingKey(), row.eventSnapshot(),
                row.claimedBy(), row.attemptCount()));
    }

    @Override
    @Transactional
    public void markPublished(ClaimedOutboxEvent event, Instant publishedAt) {
        setTarget(event.tenantId());
        if (outboxMapper.markPublished(
                event.eventId(), event.claimant(), TenantAccessTime.asOffsetDateTime(publishedAt)) != 1) {
            throw new IllegalStateException("Tenant Access Outbox Event 发布确认状态已变化");
        }
    }

    @Override
    @Transactional
    public void releaseAfterFailure(ClaimedOutboxEvent event, Instant retryAt, String failureSummary) {
        setTarget(event.tenantId());
        if (outboxMapper.releaseAfterFailure(
                event.eventId(), event.claimant(), TenantAccessTime.asOffsetDateTime(retryAt),
                failureSummary) != 1) {
            throw new IllegalStateException("Tenant Access Outbox Event 失败释放状态已变化");
        }
    }

    private void setTarget(java.util.UUID tenantId) {
        if (!tenantId.toString().equals(outboxMapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Tenant Access Outbox Tenant Operation Target 设置失败");
        }
    }
}
