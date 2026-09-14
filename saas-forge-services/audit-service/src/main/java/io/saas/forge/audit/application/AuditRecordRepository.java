package io.saas.forge.audit.application;

import java.time.Instant;

public interface AuditRecordRepository {
    boolean consume(String consumerName, AuditRecord record, Instant consumedAt);
}
