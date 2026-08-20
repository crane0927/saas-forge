package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.infrastructure.persistence.mapper.OutboxEventMapper;
import io.saasforge.iam.infrastructure.persistence.record.OutboxEventRow;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisOutboxEventRepository implements OutboxEventRepository {
    private final OutboxEventMapper mapper;

    public MyBatisOutboxEventRepository(OutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(OutboxEvent event) {
        OutboxEventRow row = new OutboxEventRow();
        row.setEventId(event.eventId());
        row.setOccurredAt(IamTime.asOffsetDateTime(event.occurredAt()));
        row.setTopic(event.topic());
        row.setOrderingKey(event.orderingKey());
        row.setTraceId(event.traceId());
        row.setEventSnapshot(event.eventSnapshot());
        if (mapper.insert(row) != 1) {
            throw new IllegalStateException("Outbox Event 保存失败");
        }
    }

    @Override
    @Transactional
    public Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant claimedUntil) {
        OutboxEventRow row = mapper.claimNext(
                claimant, IamTime.asOffsetDateTime(at), IamTime.asOffsetDateTime(claimedUntil));
        return Optional.ofNullable(row).map(value -> new ClaimedOutboxEvent(
                value.getEventId(), value.getTopic(), value.getOrderingKey(), value.getEventSnapshot(),
                value.getClaimedBy(), value.getAttemptCount()));
    }

    @Override
    public void markPublished(ClaimedOutboxEvent event, Instant publishedAt) {
        if (mapper.markPublished(event.eventId(), event.claimant(), IamTime.asOffsetDateTime(publishedAt)) != 1) {
            throw new IllegalStateException("Outbox Event 发布确认状态已变化");
        }
    }

    @Override
    public void releaseAfterFailure(ClaimedOutboxEvent event, Instant retryAt, String failureSummary) {
        if (mapper.releaseAfterFailure(event.eventId(), event.claimant(), IamTime.asOffsetDateTime(retryAt),
                failureSummary) != 1) {
            throw new IllegalStateException("Outbox Event 失败释放状态已变化");
        }
    }
}
