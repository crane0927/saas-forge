package io.saasforge.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class AuditIsolationReplayService {
    private final AuditIsolationReplayRepository repository;
    private final Clock clock;

    public AuditIsolationReplayService(AuditIsolationReplayRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AuditIsolationReplayRequestOutcome request(UUID isolationId) {
        return repository.request(isolationId, clock.instant());
    }

    @Transactional
    public Optional<ClaimedAuditIsolationReplay> claim(
            UUID isolationId, String claimant, Instant claimedAt, Instant claimedUntil) {
        return repository.claim(isolationId, claimant, claimedAt, claimedUntil);
    }

    /** Kafka 确认与状态迁移无法组成单一事务；失败时以原 Event ID 再投保持幂等。 */
    @Transactional
    public void markSucceeded(ClaimedAuditIsolationReplay replay, Instant succeededAt) {
        repository.markSucceeded(replay, succeededAt);
    }

    @Transactional
    public void releaseAfterFailure(
            ClaimedAuditIsolationReplay replay, Instant retryAt, String diagnostic) {
        repository.releaseAfterFailure(replay, retryAt, diagnostic);
    }
}
