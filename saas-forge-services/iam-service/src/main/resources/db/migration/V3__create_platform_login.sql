ALTER TABLE iam_refresh_token_families
    ADD COLUMN family_purpose TEXT;

UPDATE iam_refresh_token_families
SET family_purpose = CASE
    WHEN membership_id IS NULL THEN 'USER_PLATFORM'
    ELSE 'USER_TENANT'
END;

ALTER TABLE iam_refresh_token_families
    ALTER COLUMN family_purpose SET NOT NULL,
    ADD CONSTRAINT ck_iam_refresh_token_families_purpose CHECK (
        family_purpose IN ('USER_PLATFORM', 'USER_TENANT', 'USER_TENANT_SELECTION', 'INITIAL_PASSWORD_CHANGE')
    );

CREATE TABLE iam_platform_role_assignments (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    role_key TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_platform_role_assignments_role CHECK (char_length(btrim(role_key)) > 0),
    CONSTRAINT ck_iam_platform_role_assignments_lifecycle CHECK (revoked_at IS NULL OR revoked_at >= assigned_at)
);

CREATE UNIQUE INDEX uq_iam_platform_role_assignments_active
    ON iam_platform_role_assignments (identity_id, role_key)
    WHERE revoked_at IS NULL;

CREATE TABLE iam_access_token_issuances (
    jti UUID PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES iam_refresh_token_families (id),
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    membership_id UUID,
    tenant_id UUID,
    kid TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_reason TEXT,
    CONSTRAINT ck_iam_access_token_issuances_jti_uuidv7 CHECK (uuid_extract_version(jti) = 7),
    CONSTRAINT ck_iam_access_token_issuances_context CHECK ((membership_id IS NULL) = (tenant_id IS NULL)),
    CONSTRAINT ck_iam_access_token_issuances_lifetime CHECK (expires_at > issued_at),
    CONSTRAINT ck_iam_access_token_issuances_revocation CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_at >= issued_at AND char_length(btrim(revocation_reason)) > 0)
    )
);

CREATE INDEX ix_iam_access_token_issuances_family ON iam_access_token_issuances (family_id, expires_at);
CREATE INDEX ix_iam_access_token_issuances_kid ON iam_access_token_issuances (kid, expires_at);

CREATE TABLE iam_outbox_events (
    event_id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    topic TEXT NOT NULL,
    ordering_key TEXT NOT NULL,
    trace_id CHAR(32),
    event_snapshot JSONB NOT NULL,
    claimed_by TEXT,
    claimed_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_failure TEXT,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_outbox_events_id_uuidv7 CHECK (uuid_extract_version(event_id) = 7),
    CONSTRAINT ck_iam_outbox_events_topic CHECK (char_length(btrim(topic)) > 0),
    CONSTRAINT ck_iam_outbox_events_ordering_key CHECK (char_length(btrim(ordering_key)) > 0),
    CONSTRAINT ck_iam_outbox_events_trace CHECK (trace_id IS NULL OR trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    CONSTRAINT ck_iam_outbox_events_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_iam_outbox_events_claim CHECK ((claimed_by IS NULL) = (claimed_until IS NULL)),
    CONSTRAINT ck_iam_outbox_events_publication CHECK (published_at IS NULL OR published_at >= occurred_at)
);

CREATE INDEX ix_iam_outbox_events_pending
    ON iam_outbox_events (next_attempt_at, occurred_at)
    WHERE published_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON iam_platform_role_assignments TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_access_token_issuances TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_outbox_events TO iam_app;
