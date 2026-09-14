package io.saasforge.audit.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuditIsolationReplayRepository {
    AuditIsolationReplayRequestOutcome request(UUID isolationId, Instant requestedAt);

    Optional<ClaimedAuditIsolationReplay> claim(
            UUID isolationId, String claimant, Instant claimedAt, Instant claimedUntil);

    void markSucceeded(ClaimedAuditIsolationReplay replay, Instant succeededAt);

    void releaseAfterFailure(
            ClaimedAuditIsolationReplay replay, Instant retryAt, String diagnostic);
}
