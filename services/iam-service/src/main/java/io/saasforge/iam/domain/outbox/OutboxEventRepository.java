package io.saasforge.iam.domain.outbox;

import java.time.Instant;
import java.util.Optional;

public interface OutboxEventRepository {
    void append(OutboxEvent event);

    Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant claimedUntil);

    void markPublished(ClaimedOutboxEvent event, Instant publishedAt);

    void releaseAfterFailure(ClaimedOutboxEvent event, Instant retryAt, String failureSummary);
}
