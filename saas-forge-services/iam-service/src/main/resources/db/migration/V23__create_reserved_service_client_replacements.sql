CREATE TABLE iam_reserved_service_client_replacements (
    replacement_request_id UUID PRIMARY KEY,
    service_key TEXT NOT NULL,
    old_client_id UUID NOT NULL REFERENCES iam_oauth_clients (id),
    new_client_id UUID NOT NULL REFERENCES iam_oauth_clients (id),
    request_fingerprint BYTEA NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_iam_reserved_client_replacement_id_uuidv7
        CHECK (uuid_extract_version(replacement_request_id) = 7),
    CONSTRAINT ck_iam_reserved_client_replacement_service
        CHECK (service_key IN ('IAM', 'TENANT_ACCESS', 'ENTITLEMENT')),
    CONSTRAINT ck_iam_reserved_client_replacement_distinct_ids CHECK (old_client_id <> new_client_id),
    CONSTRAINT ck_iam_reserved_client_replacement_fingerprint CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT uq_iam_reserved_client_replacement_new_client UNIQUE (new_client_id)
);

GRANT SELECT, INSERT ON iam_reserved_service_client_replacements TO iam_app;
