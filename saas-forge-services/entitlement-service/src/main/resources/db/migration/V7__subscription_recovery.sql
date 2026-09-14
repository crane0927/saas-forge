-- 独立保留原操作者请求与结果；不改变既有订阅、额度或已发布迁移。
CREATE TABLE subscription_recovery (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    tenant_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    ends_at TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    replay_until TIMESTAMPTZ NOT NULL,
    response_body JSONB,
    UNIQUE (actor_identity_id, idempotency_key)
);
CREATE INDEX ix_subscription_recovery_actor_tenant ON subscription_recovery (actor_identity_id, tenant_id, id);
GRANT SELECT, INSERT, UPDATE ON subscription_recovery TO entitlement_app;

-- 导入现存稳定结果及原保留期限，不重授权益。TEXT 保留请求时间精度以匹配原指纹。
INSERT INTO subscription_recovery(actor_identity_id, idempotency_key, tenant_id, plan_id, ends_at,
        created_at, replay_until, response_body)
SELECT caller_identity_id, idempotency_key, (response_body->>'tenantId')::UUID,
       (response_body->>'planId')::UUID, response_body->>'endsAt', completed_at, expires_at, response_body
FROM entitlement_bootstrap_idempotency
WHERE operation_type = 'CREATE_INITIAL_SUBSCRIPTION' AND response_kind = 'SUBSCRIPTION';
