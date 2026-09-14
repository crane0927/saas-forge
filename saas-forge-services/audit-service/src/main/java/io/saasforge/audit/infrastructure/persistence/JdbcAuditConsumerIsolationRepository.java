package io.saasforge.audit.infrastructure.persistence;

import io.saasforge.audit.application.AuditConsumerIsolation;
import io.saasforge.audit.application.AuditConsumerIsolationRepository;
import io.saasforge.audit.application.AuditProcessingFailure;
import io.saasforge.audit.application.ClaimedAuditIsolationDelivery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditConsumerIsolationRepository implements AuditConsumerIsolationRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuditConsumerIsolationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void appendProcessingFailure(AuditProcessingFailure failure, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_isolation_attempts (
                    consumer_name, topic, partition_id, record_offset, event_id, action,
                    attempt_count, failure_category, diagnostic, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'PROCESSING_FAILED', ?, ?, ?, ?)
                ON CONFLICT (consumer_name, topic, partition_id, record_offset, action, attempt_count)
                DO NOTHING
                """, failure.consumerName(), failure.topic(), failure.partition(), failure.offset(),
                failure.eventId(), failure.attemptCount(), failure.failureCategory(), failure.diagnostic(),
                utc(occurredAt));
    }

    @Override
    public UUID isolate(AuditConsumerIsolation isolation, Instant isolatedAt) {
        String status = isolation.safeSnapshot() == null ? "REJECTED_NON_REPLAYABLE" : "OPEN";
        String processingFailureCategory = "PERMANENT_VALIDATION".equals(isolation.failureCategory())
                ? "PERMANENT_VALIDATION"
                : "TRANSIENT_PROCESSING";
        jdbc.update("""
                INSERT INTO audit_isolation_attempts (
                    consumer_name, topic, partition_id, record_offset, event_id, action,
                    attempt_count, failure_category, diagnostic, occurred_at)
                SELECT ?, ?, ?, ?, ?, 'PROCESSING_FAILED', attempt_number, ?, ?, ?
                FROM generate_series(1, ?) AS attempt_number
                ON CONFLICT (consumer_name, topic, partition_id, record_offset, action, attempt_count)
                DO NOTHING
                """, isolation.consumerName(), isolation.topic(), isolation.partition(),
                isolation.offset(), isolation.eventId(), processingFailureCategory,
                isolation.diagnostic(), utc(isolatedAt), isolation.attemptCount());
        List<UUID> inserted = jdbc.query("""
                WITH failure_stats AS (
                    SELECT COALESCE(min(occurred_at), ?::timestamptz) AS first_failure_at
                    FROM audit_isolation_attempts
                    WHERE consumer_name = ? AND topic = ? AND partition_id = ? AND record_offset = ?
                )
                INSERT INTO audit_consumer_isolations (
                    consumer_name, topic, partition_id, record_offset, ordering_key,
                    event_id, source, source_type, payload_sha256, failure_category, diagnostic,
                    attempt_count, first_failure_at, last_failure_at, status, safe_snapshot)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                       first_failure_at, ?, ?, ?
                FROM failure_stats
                ON CONFLICT (consumer_name, topic, partition_id, record_offset) DO NOTHING
                RETURNING isolation_id
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                utc(isolatedAt), isolation.consumerName(), isolation.topic(), isolation.partition(),
                isolation.offset(), isolation.consumerName(), isolation.topic(), isolation.partition(),
                isolation.offset(), isolation.orderingKey(), isolation.eventId(), isolation.source(),
                isolation.sourceType(), isolation.payloadSha256(), isolation.failureCategory(),
                isolation.diagnostic(), isolation.attemptCount(), utc(isolatedAt), status,
                isolation.safeSnapshot());
        UUID isolationId = inserted.isEmpty()
                ? jdbc.queryForObject("""
                        SELECT isolation_id FROM audit_consumer_isolations
                        WHERE consumer_name = ? AND topic = ? AND partition_id = ? AND record_offset = ?
                        """, UUID.class, isolation.consumerName(), isolation.topic(),
                        isolation.partition(), isolation.offset())
                : inserted.get(0);
        int attemptCount = jdbc.queryForObject(
                "SELECT attempt_count FROM audit_consumer_isolations WHERE isolation_id = ?",
                Integer.class, isolationId);
        jdbc.update("""
                INSERT INTO audit_isolation_attempts (
                    isolation_id, consumer_name, topic, partition_id, record_offset, event_id,
                    action, attempt_count, failure_category, diagnostic, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ISOLATED', ?, ?, ?, ?)
                ON CONFLICT (consumer_name, topic, partition_id, record_offset, action, attempt_count)
                DO NOTHING
                """, isolationId, isolation.consumerName(), isolation.topic(), isolation.partition(),
                isolation.offset(), isolation.eventId(), attemptCount, isolation.failureCategory(),
                isolation.diagnostic(), utc(isolatedAt));
        if (isolation.safeSnapshot() != null) {
            jdbc.update("""
                    INSERT INTO audit_isolation_deliveries (
                        isolation_id, consumer_name, topic, ordering_key, event_id, next_attempt_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (isolation_id) DO NOTHING
                    """, isolationId, isolation.consumerName(), isolation.isolationTopic(),
                    isolation.orderingKey(), isolation.eventId(), utc(isolatedAt));
        }
        return isolationId;
    }

    @Override
    public Optional<ClaimedAuditIsolationDelivery> claimNext(
            String claimant, Instant claimedAt, Instant claimedUntil) {
        List<ClaimedAuditIsolationDelivery> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT delivery_id
                    FROM audit_isolation_deliveries
                    WHERE published_at IS NULL AND next_attempt_at <= ?
                      AND (claimed_until IS NULL OR claimed_until <= ?)
                    ORDER BY next_attempt_at, delivery_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE audit_isolation_deliveries delivery
                SET claimed_by = ?, claimed_until = ?, attempt_count = delivery.attempt_count + 1
                FROM candidate, audit_consumer_isolations isolation
                WHERE delivery.delivery_id = candidate.delivery_id
                  AND isolation.isolation_id = delivery.isolation_id
                RETURNING delivery.delivery_id, delivery.isolation_id, delivery.consumer_name,
                          delivery.topic, delivery.ordering_key, delivery.event_id,
                          isolation.safe_snapshot, delivery.claimed_by, delivery.attempt_count
                """, (resultSet, rowNumber) -> new ClaimedAuditIsolationDelivery(
                        resultSet.getObject("delivery_id", UUID.class),
                        resultSet.getObject("isolation_id", UUID.class),
                        resultSet.getString("consumer_name"), resultSet.getString("topic"),
                        resultSet.getString("ordering_key"), resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("safe_snapshot"), resultSet.getString("claimed_by"),
                        resultSet.getInt("attempt_count")),
                utc(claimedAt), utc(claimedAt), claimant, utc(claimedUntil));
        return claimed.stream().findFirst();
    }

    @Override
    public void markPublished(ClaimedAuditIsolationDelivery delivery, Instant publishedAt) {
        if (jdbc.update("""
                UPDATE audit_isolation_deliveries
                SET published_at = ?, claimed_by = NULL, claimed_until = NULL, last_failure = NULL
                WHERE delivery_id = ? AND claimed_by = ? AND published_at IS NULL
                """, utc(publishedAt), delivery.deliveryId(), delivery.claimant()) != 1) {
            throw new IllegalStateException("Audit Isolation Delivery 发布确认状态已变化");
        }
        appendDeliveryAttempt(delivery, "ISOLATION_DELIVERED", "DELIVERED", publishedAt);
    }

    @Override
    public void releaseAfterFailure(
            ClaimedAuditIsolationDelivery delivery, Instant retryAt, String diagnostic) {
        if (jdbc.update("""
                UPDATE audit_isolation_deliveries
                SET claimed_by = NULL, claimed_until = NULL, next_attempt_at = ?, last_failure = ?
                WHERE delivery_id = ? AND claimed_by = ? AND published_at IS NULL
                """, utc(retryAt), diagnostic, delivery.deliveryId(), delivery.claimant()) != 1) {
            throw new IllegalStateException("Audit Isolation Delivery 失败释放状态已变化");
        }
        appendDeliveryAttempt(delivery, "ISOLATION_DELIVERY_FAILED", diagnostic, retryAt);
    }

    private void appendDeliveryAttempt(
            ClaimedAuditIsolationDelivery delivery, String action, String diagnostic, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_isolation_attempts (
                    isolation_id, consumer_name, topic, partition_id, record_offset, event_id,
                    action, attempt_count, failure_category, diagnostic, occurred_at)
                SELECT isolation_id, consumer_name, topic, partition_id, record_offset, event_id,
                       ?, ?, 'ISOLATION_DELIVERY', ?, ?
                FROM audit_consumer_isolations WHERE isolation_id = ?
                ON CONFLICT (consumer_name, topic, partition_id, record_offset, action, attempt_count)
                DO NOTHING
                """, action, delivery.attemptCount(), diagnostic, utc(occurredAt), delivery.isolationId());
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
