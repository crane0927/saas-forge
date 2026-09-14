CREATE TABLE quota_definitions (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    quota_definition_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_quota_definition_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_quota_definition_code CHECK (code = 'max_users'),
    CONSTRAINT ck_quota_definition_status
        CHECK (quota_definition_status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE plans (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    plan_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_plan_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_plan_code CHECK (code ~ '^[a-z][a-z0-9-]{1,62}$'),
    CONSTRAINT ck_plan_display_name CHECK (char_length(display_name) BETWEEN 1 AND 200),
    CONSTRAINT ck_plan_status CHECK (plan_status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE plan_quotas (
    plan_id UUID PRIMARY KEY REFERENCES plans (id),
    quota_definition_id UUID NOT NULL REFERENCES quota_definitions (id),
    quota_limit INTEGER NOT NULL,
    CONSTRAINT ck_plan_quota_limit CHECK (quota_limit >= 0)
);

CREATE TABLE entitlement_bootstrap_idempotency (
    caller_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    operation_type TEXT NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    target_id UUID NOT NULL,
    response_status INTEGER,
    response_kind TEXT,
    response_body JSONB,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (caller_identity_id, idempotency_key),
    CONSTRAINT ck_entitlement_idempotency_caller_uuidv7
        CHECK (uuid_extract_version(caller_identity_id) = 7),
    CONSTRAINT ck_entitlement_idempotency_key_uuidv7
        CHECK (uuid_extract_version(idempotency_key) = 7),
    CONSTRAINT ck_entitlement_idempotency_target_uuidv7
        CHECK (uuid_extract_version(target_id) = 7),
    CONSTRAINT ck_entitlement_idempotency_operation CHECK (operation_type IN (
        'CREATE_QUOTA_DEFINITION', 'ACTIVATE_QUOTA_DEFINITION', 'CREATE_PLAN', 'ACTIVATE_PLAN')),
    CONSTRAINT ck_entitlement_idempotency_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_entitlement_idempotency_completion CHECK (
        (response_status IS NULL) = (response_kind IS NULL)
        AND (response_status IS NULL) = (response_body IS NULL)
        AND (response_status IS NULL) = (completed_at IS NULL)),
    CONSTRAINT ck_entitlement_idempotency_response_kind
        CHECK (response_kind IS NULL OR response_kind IN ('QUOTA_DEFINITION', 'PLAN'))
);

CREATE TABLE entitlement_outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
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
    CONSTRAINT ck_entitlement_outbox_event_uuidv7 CHECK (uuid_extract_version(event_id) = 7),
    CONSTRAINT ck_entitlement_outbox_aggregate_uuidv7 CHECK (uuid_extract_version(aggregate_id) = 7),
    CONSTRAINT ck_entitlement_outbox_trace
        CHECK (trace_id IS NULL OR trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    CONSTRAINT ck_entitlement_outbox_claim CHECK ((claimed_by IS NULL) = (claimed_until IS NULL))
);

GRANT SELECT, INSERT, UPDATE ON quota_definitions TO entitlement_app;
GRANT SELECT, INSERT, UPDATE ON plans TO entitlement_app;
GRANT SELECT, INSERT ON plan_quotas TO entitlement_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON entitlement_bootstrap_idempotency TO entitlement_app;
GRANT SELECT, INSERT, UPDATE ON entitlement_outbox_events TO entitlement_app;
