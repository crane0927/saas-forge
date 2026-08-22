CREATE TABLE iam_identity_provisioning_facts (
    caller_client_id UUID NOT NULL REFERENCES iam_oauth_clients (id),
    request_id UUID NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    credential_status TEXT NOT NULL,
    ensured_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (caller_client_id, request_id),
    CONSTRAINT ck_iam_identity_provisioning_caller_uuidv7
        CHECK (uuid_extract_version(caller_client_id) = 7),
    CONSTRAINT ck_iam_identity_provisioning_request_uuidv7
        CHECK (uuid_extract_version(request_id) = 7),
    CONSTRAINT ck_iam_identity_provisioning_fingerprint_sha256
        CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT ck_iam_identity_provisioning_credential_status
        CHECK (credential_status IN ('SETUP_ALLOWED', 'PASSWORD_READY', 'RECOVERY_REQUIRED'))
);

GRANT SELECT, INSERT ON iam_identity_provisioning_facts TO iam_app;
