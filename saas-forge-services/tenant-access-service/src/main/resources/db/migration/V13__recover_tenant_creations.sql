-- 登记先独立持久化，结果与 Tenant 创建在同一事务完成；不复制其他领域工作流。
CREATE TABLE tenant_creation_recovery (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    actor_identity_id UUID NOT NULL CHECK (uuid_extract_version(actor_identity_id) = 7),
    idempotency_key UUID NOT NULL CHECK (uuid_extract_version(idempotency_key) = 7),
    display_name VARCHAR(200) NOT NULL,
    tenant_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    replay_until TIMESTAMPTZ NOT NULL,
    response_body JSONB,
    UNIQUE (actor_identity_id, idempotency_key),
    CHECK (uuid_extract_version(id) = 7)
);
CREATE INDEX ix_tenant_creation_recovery_actor ON tenant_creation_recovery(actor_identity_id, id);
GRANT SELECT, INSERT, UPDATE ON tenant_creation_recovery TO tenant_access_app;
