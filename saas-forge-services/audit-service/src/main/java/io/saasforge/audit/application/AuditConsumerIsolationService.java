package io.saasforge.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditConsumerIsolationService {
    private final AuditConsumerIsolationRepository repository;
    private final Clock clock;

    public AuditConsumerIsolationService(AuditConsumerIsolationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 失败轨迹必须脱离已回滚的 Audit Record 事务单独提交。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProcessingFailure(AuditProcessingFailure failure) {
        repository.appendProcessingFailure(failure, clock.instant());
    }

    /** 隔离记录与可选可靠投递状态必须在同一本地事务建立。 */
    @Transactional
    public UUID isolate(AuditConsumerIsolation isolation) {
        return repository.isolate(isolation, clock.instant());
    }

    @Transactional
    public Optional<ClaimedAuditIsolationDelivery> claimNext(
            String claimant, Instant claimedAt, Instant claimedUntil) {
        return repository.claimNext(claimant, claimedAt, claimedUntil);
    }

    @Transactional
    public void markPublished(ClaimedAuditIsolationDelivery delivery, Instant publishedAt) {
        repository.markPublished(delivery, publishedAt);
    }

    @Transactional
    public void releaseAfterFailure(
            ClaimedAuditIsolationDelivery delivery, Instant retryAt, String diagnostic) {
        repository.releaseAfterFailure(delivery, retryAt, diagnostic);
    }
}
