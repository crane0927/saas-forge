package io.saasforge.tenantaccess.domain.outbox;

import java.time.Instant;
import java.util.Optional;

public interface OutboxEventRepository {
    void append(OutboxEvent event);

    default Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant claimedUntil) {
        return Optional.empty();
    }

    default void markPublished(ClaimedOutboxEvent event, Instant publishedAt) {
        throw new UnsupportedOperationException("当前 Outbox Repository 不支持发布确认");
    }

    default void releaseAfterFailure(ClaimedOutboxEvent event, Instant retryAt, String failureSummary) {
        throw new UnsupportedOperationException("当前 Outbox Repository 不支持失败释放");
    }
}
