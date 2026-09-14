CREATE TABLE iam_platform_admin_bootstrap_facts (
    bootstrap_key TEXT PRIMARY KEY,
    identity_id UUID NOT NULL UNIQUE REFERENCES iam_identities (id),
    credential_id UUID NOT NULL UNIQUE REFERENCES iam_credentials (id),
    role_assignment_id UUID NOT NULL UNIQUE REFERENCES iam_platform_role_assignments (id),
    event_id UUID NOT NULL UNIQUE REFERENCES iam_outbox_events (event_id),
    initialized_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_iam_platform_admin_bootstrap_key CHECK (bootstrap_key = 'DEFAULT_PLATFORM_ADMIN')
);

GRANT SELECT, INSERT ON iam_platform_admin_bootstrap_facts TO iam_app;
