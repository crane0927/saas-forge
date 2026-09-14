package io.saasforge.audit.application;

import java.time.Instant;

public interface AuditRecordRepository {
    boolean consume(String consumerName, AuditRecord record, Instant consumedAt);
}
