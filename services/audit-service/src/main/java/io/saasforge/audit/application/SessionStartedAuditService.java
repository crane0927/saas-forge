package io.saasforge.audit.application;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionStartedAuditService {
    private final AuditRecordRepository repository;
    private final Clock clock;

    public SessionStartedAuditService(AuditRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 去重键与 Audit Record 必须在同一本地事务提交；返回后 Kafka 才可以确认。 */
    @Transactional
    public boolean record(String consumerName, SessionStartedAuditRecord record) {
        return repository.consume(consumerName, record, clock.instant());
    }
}
