CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE,
    plan_id UUID NOT NULL REFERENCES plans (id),
    subscription_status TEXT NOT NULL,
    ends_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_subscription_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_subscription_tenant_uuidv7 CHECK (uuid_extract_version(tenant_id) = 7),
    CONSTRAINT ck_subscription_status CHECK (subscription_status = 'ACTIVE'),
    CONSTRAINT ck_subscription_ends_at CHECK (ends_at IS NULL OR ends_at > created_at)
);

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;

CREATE POLICY subscriptions_runtime_access ON subscriptions
    FOR ALL TO entitlement_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY subscriptions_migration_access ON subscriptions
    FOR ALL TO entitlement_migrator
    USING (true)
    WITH CHECK (true);

ALTER TABLE entitlement_bootstrap_idempotency
    DROP CONSTRAINT ck_entitlement_idempotency_operation,
    ADD CONSTRAINT ck_entitlement_idempotency_operation CHECK (operation_type IN (
        'CREATE_QUOTA_DEFINITION', 'ACTIVATE_QUOTA_DEFINITION', 'CREATE_PLAN', 'ACTIVATE_PLAN',
        'CREATE_INITIAL_SUBSCRIPTION')),
    DROP CONSTRAINT ck_entitlement_idempotency_response_kind,
    ADD CONSTRAINT ck_entitlement_idempotency_response_kind
        CHECK (response_kind IS NULL OR response_kind IN ('QUOTA_DEFINITION', 'PLAN', 'SUBSCRIPTION'));

GRANT SELECT, INSERT ON subscriptions TO entitlement_app;
