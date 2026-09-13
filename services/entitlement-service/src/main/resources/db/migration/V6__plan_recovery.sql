-- 操作登记独立提交；业务结果与原有领域、幂等及 Outbox 在同一事务提交。
CREATE TABLE plan_recovery (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('CREATE', 'ACTIVATE')),
    target_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    replay_until TIMESTAMPTZ NOT NULL,
    response_body JSONB,
    request_body JSONB,
    UNIQUE (actor_identity_id, idempotency_key),
    CHECK ((operation = 'CREATE' AND target_id IS NULL) OR (operation = 'ACTIVATE' AND target_id IS NOT NULL))
);
CREATE INDEX ix_plan_recovery_actor ON plan_recovery (actor_identity_id, id);
GRANT SELECT, INSERT, UPDATE ON plan_recovery TO entitlement_app;

-- 保留升级前稳定响应和原保留期；不会重放命令或改变历史权益。
INSERT INTO plan_recovery (actor_identity_id, idempotency_key, operation, target_id,
        created_at, replay_until, response_body, request_body)
SELECT caller_identity_id, idempotency_key,
       CASE operation_type WHEN 'CREATE_PLAN' THEN 'CREATE' ELSE 'ACTIVATE' END,
       CASE operation_type WHEN 'ACTIVATE_PLAN' THEN target_id ELSE NULL END,
       completed_at, expires_at, response_body,
       CASE operation_type WHEN 'CREATE_PLAN' THEN jsonb_build_object(
           'code', response_body->>'code', 'displayName', response_body->>'displayName',
           'quotaDefinitionId', response_body->'quotaLimits'->0->>'quotaDefinitionId',
           'limit', response_body->'quotaLimits'->0->'limit') ELSE NULL END
FROM entitlement_bootstrap_idempotency
WHERE operation_type IN ('CREATE_PLAN', 'ACTIVATE_PLAN') AND response_kind = 'PLAN';
