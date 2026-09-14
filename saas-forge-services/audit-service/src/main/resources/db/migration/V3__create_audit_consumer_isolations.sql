CREATE TABLE audit_consumer_isolations (
    isolation_id UUID PRIMARY KEY DEFAULT uuidv7(),
    consumer_name TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    ordering_key TEXT,
    event_id UUID,
    source TEXT,
    source_type TEXT,
    payload_sha256 CHAR(64) NOT NULL,
    failure_category TEXT NOT NULL,
    diagnostic TEXT NOT NULL,
    attempt_count INTEGER NOT NULL,
    first_failure_at TIMESTAMPTZ NOT NULL,
    last_failure_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    safe_snapshot TEXT,
    UNIQUE (consumer_name, topic, partition_id, record_offset),
    CONSTRAINT ck_audit_consumer_isolations_location CHECK (partition_id >= 0 AND record_offset >= 0),
    CONSTRAINT ck_audit_consumer_isolations_payload_sha256 CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_consumer_isolations_failure_category CHECK (
        failure_category IN ('PERMANENT_VALIDATION', 'RETRY_EXHAUSTED')
    ),
    CONSTRAINT ck_audit_consumer_isolations_attempt_count CHECK (attempt_count > 0),
    CONSTRAINT ck_audit_consumer_isolations_failure_time CHECK (last_failure_at >= first_failure_at),
    CONSTRAINT ck_audit_consumer_isolations_status CHECK (
        status IN ('OPEN', 'REPLAY_REQUESTED', 'RESOLVED', 'REJECTED_NON_REPLAYABLE')
    ),
    CONSTRAINT ck_audit_consumer_isolations_snapshot_status CHECK (
        (status = 'REJECTED_NON_REPLAYABLE' AND safe_snapshot IS NULL)
        OR (status <> 'REJECTED_NON_REPLAYABLE' AND safe_snapshot IS NOT NULL)
    )
);

CREATE TABLE audit_isolation_attempts (
    attempt_id UUID PRIMARY KEY DEFAULT uuidv7(),
    isolation_id UUID REFERENCES audit_consumer_isolations(isolation_id),
    consumer_name TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    event_id UUID,
    action TEXT NOT NULL,
    attempt_count INTEGER NOT NULL,
    failure_category TEXT NOT NULL,
    diagnostic TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_audit_isolation_attempts_location CHECK (partition_id >= 0 AND record_offset >= 0),
    CONSTRAINT ck_audit_isolation_attempts_action CHECK (
        action IN ('PROCESSING_FAILED', 'ISOLATED', 'ISOLATION_DELIVERY_FAILED', 'ISOLATION_DELIVERED')
    ),
    CONSTRAINT ck_audit_isolation_attempts_attempt_count CHECK (attempt_count > 0)
);

CREATE UNIQUE INDEX uq_audit_isolation_attempts_message_action
    ON audit_isolation_attempts (
        consumer_name, topic, partition_id, record_offset, action, attempt_count
    );

CREATE TABLE audit_isolation_deliveries (
    delivery_id UUID PRIMARY KEY DEFAULT uuidv7(),
    isolation_id UUID NOT NULL UNIQUE REFERENCES audit_consumer_isolations(isolation_id),
    consumer_name TEXT NOT NULL,
    topic TEXT NOT NULL,
    ordering_key TEXT,
    event_id UUID NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claimed_by TEXT,
    claimed_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_failure TEXT,
    CONSTRAINT ck_audit_isolation_deliveries_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_audit_isolation_deliveries_lease CHECK (
        (claimed_by IS NULL AND claimed_until IS NULL)
        OR (claimed_by IS NOT NULL AND claimed_until IS NOT NULL)
    )
);

CREATE INDEX ix_audit_isolation_deliveries_claim
    ON audit_isolation_deliveries (next_attempt_at, delivery_id)
    WHERE published_at IS NULL;

REVOKE ALL ON audit_consumer_isolations FROM audit_app;
GRANT SELECT, INSERT ON audit_consumer_isolations TO audit_app;
GRANT UPDATE (status) ON audit_consumer_isolations TO audit_app;

REVOKE ALL ON audit_isolation_attempts FROM audit_app;
GRANT SELECT, INSERT ON audit_isolation_attempts TO audit_app;

REVOKE ALL ON audit_isolation_deliveries FROM audit_app;
GRANT SELECT, INSERT ON audit_isolation_deliveries TO audit_app;
GRANT UPDATE (attempt_count, next_attempt_at, claimed_by, claimed_until, published_at, last_failure)
    ON audit_isolation_deliveries TO audit_app;
