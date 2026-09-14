CREATE TABLE iam_platform_admin_credential_reset_facts (
    reset_request_id UUID PRIMARY KEY,
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    credential_id UUID NOT NULL UNIQUE REFERENCES iam_credentials (id),
    event_id UUID NOT NULL UNIQUE REFERENCES iam_outbox_events (event_id),
    reset_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_iam_platform_admin_credential_reset_request_uuidv7
        CHECK (uuid_extract_version(reset_request_id) = 7)
);

GRANT SELECT, INSERT ON iam_platform_admin_credential_reset_facts TO iam_app;
