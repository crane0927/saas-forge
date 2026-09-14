CREATE TABLE quota_usages (
    tenant_id UUID NOT NULL,
    quota_definition_id UUID NOT NULL REFERENCES quota_definitions (id),
    used INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, quota_definition_id),
    CONSTRAINT ck_quota_usage_tenant_uuidv7 CHECK (uuid_extract_version(tenant_id) = 7),
    CONSTRAINT ck_quota_usage_nonnegative CHECK (used >= 0)
);

CREATE TABLE quota_operations (
    operation_id UUID PRIMARY KEY,
    caller_client_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    quota_code TEXT NOT NULL,
    amount INTEGER NOT NULL,
    operation_action TEXT NOT NULL,
    purpose TEXT NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    outcome TEXT,
    response_usage INTEGER,
    response_limit INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_quota_operation_uuidv7 CHECK (uuid_extract_version(operation_id) = 7),
    CONSTRAINT ck_quota_operation_client_uuidv7 CHECK (uuid_extract_version(caller_client_id) = 7),
    CONSTRAINT ck_quota_operation_tenant_uuidv7 CHECK (uuid_extract_version(tenant_id) = 7),
    CONSTRAINT ck_quota_operation_code CHECK (quota_code = 'max_users'),
    CONSTRAINT ck_quota_operation_amount CHECK (amount = 1),
    CONSTRAINT ck_quota_operation_action CHECK (operation_action IN ('CONSUME', 'RELEASE')),
    CONSTRAINT ck_quota_operation_purpose CHECK (purpose = 'TENANT_ADMIN_INITIALIZATION'),
    CONSTRAINT ck_quota_operation_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_quota_operation_outcome CHECK (outcome IS NULL OR outcome IN (
        'SUCCESS', 'QUOTA_DEFINITION_NOT_FOUND', 'SUBSCRIPTION_REQUIRED',
        'QUOTA_EXCEEDED', 'QUOTA_RELEASE_UNDERFLOW')),
    CONSTRAINT ck_quota_operation_completion CHECK (
        (outcome IS NULL AND completed_at IS NULL
            AND response_usage IS NULL AND response_limit IS NULL)
        OR (outcome = 'SUCCESS' AND completed_at IS NOT NULL
            AND response_usage IS NOT NULL AND response_limit IS NOT NULL)
        OR (outcome IS NOT NULL AND outcome <> 'SUCCESS' AND completed_at IS NOT NULL)),
    CONSTRAINT ck_quota_operation_response_nonnegative CHECK (
        (response_usage IS NULL OR response_usage >= 0)
        AND (response_limit IS NULL OR response_limit >= 0))
);

CREATE INDEX ix_quota_operations_tenant ON quota_operations (tenant_id, created_at);

ALTER TABLE quota_usages ENABLE ROW LEVEL SECURITY;
ALTER TABLE quota_usages FORCE ROW LEVEL SECURITY;
ALTER TABLE quota_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE quota_operations FORCE ROW LEVEL SECURITY;

CREATE POLICY quota_usages_runtime_access ON quota_usages
    FOR ALL TO entitlement_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY quota_usages_migration_access ON quota_usages
    FOR ALL TO entitlement_migrator
    USING (true)
    WITH CHECK (true);

CREATE POLICY quota_operations_runtime_access ON quota_operations
    FOR ALL TO entitlement_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY quota_operations_migration_access ON quota_operations
    FOR ALL TO entitlement_migrator
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON quota_usages TO entitlement_app;
GRANT SELECT, INSERT, UPDATE ON quota_operations TO entitlement_app;
