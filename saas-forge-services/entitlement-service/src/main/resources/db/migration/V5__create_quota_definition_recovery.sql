-- 操作登记独立提交；业务结果与原有领域、幂等及 Outbox 在同一事务提交。
CREATE TABLE quota_definition_recovery (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('CREATE', 'ACTIVATE')),
    target_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    replay_until TIMESTAMPTZ NOT NULL,
    response_body JSONB,
    UNIQUE (actor_identity_id, idempotency_key),
    CHECK ((operation = 'CREATE' AND target_id IS NULL) OR (operation = 'ACTIVATE' AND target_id IS NOT NULL))
);
CREATE INDEX ix_quota_definition_recovery_actor ON quota_definition_recovery (actor_identity_id, id);
GRANT SELECT, INSERT, UPDATE ON quota_definition_recovery TO entitlement_app;
