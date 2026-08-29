package io.saasforge.audit.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuditConsumerIsolationRepository {
    void appendProcessingFailure(AuditProcessingFailure failure, Instant occurredAt);

    UUID isolate(AuditConsumerIsolation isolation, Instant isolatedAt);

    Optional<ClaimedAuditIsolationDelivery> claimNext(
            String claimant, Instant claimedAt, Instant claimedUntil);

    void markPublished(ClaimedAuditIsolationDelivery delivery, Instant publishedAt);

    void releaseAfterFailure(
            ClaimedAuditIsolationDelivery delivery, Instant retryAt, String diagnostic);
}
