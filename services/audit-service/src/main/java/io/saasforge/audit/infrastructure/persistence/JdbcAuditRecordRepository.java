package io.saasforge.audit.infrastructure.persistence;

import io.saasforge.audit.application.AuditRecordRepository;
import io.saasforge.audit.application.SessionStartedAuditRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRecordRepository implements AuditRecordRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuditRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean consume(String consumerName, SessionStartedAuditRecord record, Instant consumedAt) {
        int inserted = jdbc.update("""
                INSERT INTO audit_consumed_events (consumer_name, event_id, source, source_type, consumed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, consumerName, record.sourceEventId(), record.source(), record.sourceType(),
                consumedAt.atOffset(ZoneOffset.UTC));
        if (inserted == 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO audit_records (
                    source_event_id, source, source_type, occurred_at, recorded_at, trace_id,
                    actor_identity_id, tenant_id, action, resource_type, resource_id, result, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'SESSION_STARTED', 'REFRESH_TOKEN_FAMILY', ?, 'SUCCESS', ?::jsonb)
                """, record.sourceEventId(), record.source(), record.sourceType(),
                record.occurredAt().atOffset(ZoneOffset.UTC), consumedAt.atOffset(ZoneOffset.UTC),
                record.traceId(), record.actorIdentityId(), record.resourceId(), record.metadata());
        return true;
    }
}
