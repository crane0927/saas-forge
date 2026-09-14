package io.saasforge.audit.infrastructure.persistence;

import io.saasforge.audit.application.AuditIsolationReplayRejectedException;
import io.saasforge.audit.application.AuditIsolationReplayRepository;
import io.saasforge.audit.application.AuditIsolationReplayRequestOutcome;
import io.saasforge.audit.application.ClaimedAuditIsolationReplay;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAuditIsolationReplayRepository implements AuditIsolationReplayRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuditIsolationReplayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuditIsolationReplayRequestOutcome request(UUID isolationId, Instant requestedAt) {
        List<IsolationState> states = jdbc.query("""
                SELECT status, safe_snapshot
                FROM audit_consumer_isolations
                WHERE isolation_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new IsolationState(
                        resultSet.getString("status"), resultSet.getString("safe_snapshot")), isolationId);
        if (states.isEmpty()) {
            throw new AuditIsolationReplayRejectedException("Audit Isolation 不存在");
        }
        IsolationState isolation = states.get(0);
        if ("REJECTED_NON_REPLAYABLE".equals(isolation.status()) || isolation.safeSnapshot() == null) {
            throw new AuditIsolationReplayRejectedException("Audit Isolation 不包含已验证安全快照");
        }
        if ("OPEN".equals(isolation.status())) {
            jdbc.update("""
                    UPDATE audit_consumer_isolations
                    SET status = 'REPLAY_REQUESTED'
                    WHERE isolation_id = ? AND status = 'OPEN'
                    """, isolationId);
            jdbc.update("""
                    INSERT INTO audit_isolation_replays (isolation_id, next_attempt_at)
                    VALUES (?, ?)
                    """, isolationId, utc(requestedAt));
            appendAttempt(isolationId, "REPLAY_REQUESTED", 1, "REQUESTED", requestedAt);
            return AuditIsolationReplayRequestOutcome.REQUESTED;
        }
        if ("REPLAY_REQUESTED".equals(isolation.status()) || "RESOLVED".equals(isolation.status())) {
            int requestCount = jdbc.queryForObject("""
                    UPDATE audit_isolation_replays
                    SET request_count = request_count + 1
                    WHERE isolation_id = ?
                    RETURNING request_count
                    """, Integer.class, isolationId);
            appendAttempt(isolationId, "REPLAY_REQUESTED", requestCount, "IDEMPOTENT_REQUEST", requestedAt);
            return "RESOLVED".equals(isolation.status())
                    ? AuditIsolationReplayRequestOutcome.ALREADY_RESOLVED
                    : AuditIsolationReplayRequestOutcome.ALREADY_REQUESTED;
        }
        throw new AuditIsolationReplayRejectedException("Audit Isolation 状态不可重放: " + isolation.status());
    }

    @Override
    public Optional<ClaimedAuditIsolationReplay> claim(
            UUID isolationId, String claimant, Instant claimedAt, Instant claimedUntil) {
        List<ClaimedAuditIsolationReplay> claimed = jdbc.query("""
                UPDATE audit_isolation_replays replay
                SET claimed_by = ?, claimed_until = ?,
                    send_attempt_count = replay.send_attempt_count + 1
                FROM audit_consumer_isolations isolation
                WHERE replay.isolation_id = ?
                  AND isolation.isolation_id = replay.isolation_id
                  AND isolation.status = 'REPLAY_REQUESTED'
                  AND replay.published_at IS NULL
                  AND replay.next_attempt_at <= ?
                  AND (replay.claimed_until IS NULL OR replay.claimed_until <= ?)
                RETURNING replay.replay_id, replay.isolation_id, isolation.topic,
                          isolation.ordering_key, isolation.event_id, isolation.safe_snapshot,
                          replay.claimed_by, replay.send_attempt_count
                """, (resultSet, rowNumber) -> new ClaimedAuditIsolationReplay(
                        resultSet.getObject("replay_id", UUID.class),
                        resultSet.getObject("isolation_id", UUID.class),
                        resultSet.getString("topic"), resultSet.getString("ordering_key"),
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("safe_snapshot"), resultSet.getString("claimed_by"),
                        resultSet.getInt("send_attempt_count")),
                claimant, utc(claimedUntil), isolationId, utc(claimedAt), utc(claimedAt));
        claimed.stream().findFirst().ifPresent(replay ->
                appendAttempt(replay.isolationId(), "REPLAY_SENT", replay.attemptCount(),
                        "SEND_STARTED", claimedAt));
        return claimed.stream().findFirst();
    }

    @Override
    public void markSucceeded(ClaimedAuditIsolationReplay replay, Instant succeededAt) {
        if (jdbc.update("""
                UPDATE audit_isolation_replays
                SET published_at = ?, claimed_by = NULL, claimed_until = NULL, last_failure = NULL
                WHERE replay_id = ? AND claimed_by = ? AND published_at IS NULL
                """, utc(succeededAt), replay.replayId(), replay.claimant()) != 1) {
            throw new IllegalStateException("Audit Isolation Replay 成功确认状态已变化");
        }
        if (jdbc.update("""
                UPDATE audit_consumer_isolations
                SET status = 'RESOLVED'
                WHERE isolation_id = ? AND status = 'REPLAY_REQUESTED'
                """, replay.isolationId()) != 1) {
            throw new IllegalStateException("Audit Isolation Replay 处置状态已变化");
        }
        appendAttempt(replay.isolationId(), "REPLAY_SUCCEEDED", replay.attemptCount(),
                "KAFKA_ACKNOWLEDGED", succeededAt);
    }

    @Override
    public void releaseAfterFailure(
            ClaimedAuditIsolationReplay replay, Instant retryAt, String diagnostic) {
        if (jdbc.update("""
                UPDATE audit_isolation_replays
                SET claimed_by = NULL, claimed_until = NULL, next_attempt_at = ?, last_failure = ?
                WHERE replay_id = ? AND claimed_by = ? AND published_at IS NULL
                """, utc(retryAt), diagnostic, replay.replayId(), replay.claimant()) != 1) {
            throw new IllegalStateException("Audit Isolation Replay 失败释放状态已变化");
        }
        appendAttempt(replay.isolationId(), "REPLAY_FAILED", replay.attemptCount(), diagnostic, retryAt);
    }

    private void appendAttempt(
            UUID isolationId, String action, int attemptCount, String diagnostic, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_isolation_attempts (
                    isolation_id, consumer_name, topic, partition_id, record_offset, event_id,
                    action, attempt_count, failure_category, diagnostic, occurred_at)
                SELECT isolation_id, consumer_name, topic, partition_id, record_offset, event_id,
                       ?, ?, 'REPLAY', ?, ?
                FROM audit_consumer_isolations WHERE isolation_id = ?
                ON CONFLICT (consumer_name, topic, partition_id, record_offset, action, attempt_count)
                DO NOTHING
                """, action, attemptCount, diagnostic, utc(occurredAt), isolationId);
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record IsolationState(String status, String safeSnapshot) {}
}
