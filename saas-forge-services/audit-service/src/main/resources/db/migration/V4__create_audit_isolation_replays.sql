ALTER TABLE audit_isolation_attempts
    DROP CONSTRAINT ck_audit_isolation_attempts_action;

ALTER TABLE audit_isolation_attempts
    ADD CONSTRAINT ck_audit_isolation_attempts_action CHECK (
        action IN (
            'PROCESSING_FAILED', 'ISOLATED',
            'ISOLATION_DELIVERY_FAILED', 'ISOLATION_DELIVERED',
            'REPLAY_REQUESTED', 'REPLAY_SENT', 'REPLAY_FAILED', 'REPLAY_SUCCEEDED'
        )
    );

CREATE TABLE audit_isolation_replays (
    replay_id UUID PRIMARY KEY DEFAULT uuidv7(),
    isolation_id UUID NOT NULL UNIQUE REFERENCES audit_consumer_isolations(isolation_id),
    request_count INTEGER NOT NULL DEFAULT 1,
    send_attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claimed_by TEXT,
    claimed_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_failure TEXT,
    CONSTRAINT ck_audit_isolation_replays_request_count CHECK (request_count > 0),
    CONSTRAINT ck_audit_isolation_replays_send_attempt_count CHECK (send_attempt_count >= 0),
    CONSTRAINT ck_audit_isolation_replays_lease CHECK (
        (claimed_by IS NULL AND claimed_until IS NULL)
        OR (claimed_by IS NOT NULL AND claimed_until IS NOT NULL)
    )
);

CREATE INDEX ix_audit_isolation_replays_claim
    ON audit_isolation_replays (next_attempt_at, replay_id)
    WHERE published_at IS NULL;

REVOKE ALL ON audit_isolation_replays FROM audit_app;
GRANT SELECT, INSERT ON audit_isolation_replays TO audit_app;
GRANT UPDATE (
    request_count, send_attempt_count, next_attempt_at,
    claimed_by, claimed_until, published_at, last_failure
) ON audit_isolation_replays TO audit_app;
