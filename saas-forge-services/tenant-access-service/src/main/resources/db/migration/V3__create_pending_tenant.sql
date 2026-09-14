ALTER TABLE tenants
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp();

CREATE TABLE tenant_creation_idempotency (
    caller_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    tenant_id UUID NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (caller_identity_id, idempotency_key),
    CONSTRAINT ck_tenant_creation_idempotency_caller_uuidv7
        CHECK (uuid_extract_version(caller_identity_id) = 7),
    CONSTRAINT ck_tenant_creation_idempotency_key_uuidv7
        CHECK (uuid_extract_version(idempotency_key) = 7),
    CONSTRAINT ck_tenant_creation_idempotency_tenant_uuidv7
        CHECK (uuid_extract_version(tenant_id) = 7),
    CONSTRAINT ck_tenant_creation_idempotency_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_creation_idempotency_completion
        CHECK ((response_status IS NULL) = (response_body IS NULL)
            AND (response_status IS NULL) = (completed_at IS NULL))
);

CREATE TABLE tenant_access_outbox_events (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    occurred_at TIMESTAMPTZ NOT NULL,
    topic TEXT NOT NULL,
    ordering_key TEXT NOT NULL,
    trace_id CHAR(32),
    event_snapshot JSONB NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claimed_by TEXT,
    claimed_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    last_failure TEXT,
    CONSTRAINT ck_tenant_access_outbox_event_uuidv7 CHECK (uuid_extract_version(event_id) = 7),
    CONSTRAINT ck_tenant_access_outbox_trace
        CHECK (trace_id IS NULL OR trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    CONSTRAINT ck_tenant_access_outbox_claim CHECK ((claimed_by IS NULL) = (claimed_until IS NULL))
);

ALTER TABLE tenant_access_outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_access_outbox_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_access_outbox_runtime_access ON tenant_access_outbox_events
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_access_outbox_migration_access ON tenant_access_outbox_events
    FOR ALL TO tenant_access_migrator
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_creation_idempotency TO tenant_access_app;
GRANT SELECT, INSERT, UPDATE ON tenant_access_outbox_events TO tenant_access_app;
