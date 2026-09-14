ALTER TABLE iam_oauth_clients
    ADD COLUMN client_type TEXT,
    ADD COLUMN reserved_service_key TEXT,
    ADD COLUMN updated_at TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM iam_oauth_clients
        WHERE NOT (
            (cardinality(allowed_scopes) BETWEEN 1 AND 2
                AND allowed_scopes <@ ARRAY['runtime:read', 'runtime:quota:write']::TEXT[]
                AND cardinality(array_positions(allowed_scopes, 'runtime:read')) <= 1
                AND cardinality(array_positions(allowed_scopes, 'runtime:quota:write')) <= 1)
            OR (cardinality(allowed_scopes) = 1
                AND allowed_scopes @> ARRAY['tenant-access:membership:read']::TEXT[]
                AND allowed_scopes <@ ARRAY['tenant-access:membership:read']::TEXT[])
            OR (cardinality(allowed_scopes) = 5
                AND allowed_scopes @> ARRAY['iam:identity:write', 'iam:password-setup:write',
                    'iam:platform-role:read', 'iam:sessions:write', 'entitlement:quota:write']::TEXT[]
                AND allowed_scopes <@ ARRAY['iam:identity:write', 'iam:password-setup:write',
                    'iam:platform-role:read', 'iam:sessions:write', 'entitlement:quota:write']::TEXT[])
            OR (cardinality(allowed_scopes) = 2
                AND allowed_scopes @> ARRAY['tenant-access:tenant:read', 'iam:platform-role:read']::TEXT[]
                AND allowed_scopes <@ ARRAY['tenant-access:tenant:read', 'iam:platform-role:read']::TEXT[])
        )
    ) THEN
        RAISE EXCEPTION 'OAuth Client 历史 Scope 组合无法安全分类';
    END IF;
END $$;

UPDATE iam_oauth_clients
SET client_type = CASE
        WHEN allowed_scopes <@ ARRAY['runtime:read', 'runtime:quota:write']::TEXT[] THEN 'RUNTIME_SERVICE'
        ELSE 'RESERVED_SERVICE'
    END,
    reserved_service_key = CASE
        WHEN cardinality(allowed_scopes) = 1
            AND allowed_scopes @> ARRAY['tenant-access:membership:read']::TEXT[] THEN 'IAM'
        WHEN cardinality(allowed_scopes) = 5
            AND allowed_scopes @> ARRAY['iam:identity:write', 'iam:password-setup:write',
                'iam:platform-role:read', 'iam:sessions:write', 'entitlement:quota:write']::TEXT[] THEN 'TENANT_ACCESS'
        WHEN cardinality(allowed_scopes) = 2
            AND allowed_scopes @> ARRAY['tenant-access:tenant:read', 'iam:platform-role:read']::TEXT[] THEN 'ENTITLEMENT'
        ELSE NULL
    END,
    updated_at = created_at;

ALTER TABLE iam_oauth_clients
    DROP CONSTRAINT ck_iam_oauth_clients_scopes,
    ALTER COLUMN client_type SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT ck_iam_oauth_clients_type CHECK (client_type IN ('RUNTIME_SERVICE', 'RESERVED_SERVICE')),
    ADD CONSTRAINT ck_iam_oauth_clients_type_key CHECK (
        (client_type = 'RUNTIME_SERVICE' AND reserved_service_key IS NULL)
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key IN ('IAM', 'TENANT_ACCESS', 'ENTITLEMENT'))
    ),
    ADD CONSTRAINT ck_iam_oauth_clients_scopes CHECK (
        (client_type = 'RUNTIME_SERVICE'
            AND cardinality(allowed_scopes) BETWEEN 1 AND 2
            AND allowed_scopes <@ ARRAY['runtime:read', 'runtime:quota:write']::TEXT[]
            AND cardinality(array_positions(allowed_scopes, 'runtime:read')) <= 1
            AND cardinality(array_positions(allowed_scopes, 'runtime:quota:write')) <= 1)
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'IAM'
            AND cardinality(allowed_scopes) = 1
            AND allowed_scopes @> ARRAY['tenant-access:membership:read']::TEXT[]
            AND allowed_scopes <@ ARRAY['tenant-access:membership:read']::TEXT[])
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'TENANT_ACCESS'
            AND cardinality(allowed_scopes) = 5
            AND allowed_scopes @> ARRAY['iam:identity:write', 'iam:password-setup:write',
                'iam:platform-role:read', 'iam:sessions:write', 'entitlement:quota:write']::TEXT[]
            AND allowed_scopes <@ ARRAY['iam:identity:write', 'iam:password-setup:write',
                'iam:platform-role:read', 'iam:sessions:write', 'entitlement:quota:write']::TEXT[])
        OR (client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'ENTITLEMENT'
            AND cardinality(allowed_scopes) = 2
            AND allowed_scopes @> ARRAY['tenant-access:tenant:read', 'iam:platform-role:read']::TEXT[]
            AND allowed_scopes <@ ARRAY['tenant-access:tenant:read', 'iam:platform-role:read']::TEXT[])
    ),
    ADD CONSTRAINT ck_iam_oauth_clients_updated CHECK (updated_at >= created_at);

CREATE UNIQUE INDEX uq_iam_oauth_clients_one_active_reserved
    ON iam_oauth_clients (reserved_service_key)
    WHERE client_type = 'RESERVED_SERVICE' AND client_status = 'ACTIVE';

CREATE TABLE iam_oauth_client_management_operations (
    id UUID PRIMARY KEY,
    actor_identity_id UUID NOT NULL REFERENCES iam_identities (id),
    idempotency_key UUID NOT NULL,
    operation_type TEXT NOT NULL,
    client_id UUID NOT NULL REFERENCES iam_oauth_clients (id),
    request_fingerprint BYTEA NOT NULL,
    original_operation_id UUID REFERENCES iam_oauth_client_management_operations (id),
    secret_record_id UUID REFERENCES iam_oauth_client_secrets (id),
    outcome TEXT NOT NULL,
    http_status INTEGER NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_iam_oauth_client_operations_id_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_iam_oauth_client_operations_key_uuidv7 CHECK (uuid_extract_version(idempotency_key) = 7),
    CONSTRAINT ck_iam_oauth_client_operations_type CHECK (
        operation_type IN ('CREATE', 'ROTATE', 'RECOVER', 'REVOKE')
    ),
    CONSTRAINT ck_iam_oauth_client_operations_fingerprint CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT ck_iam_oauth_client_operations_outcome CHECK (outcome IN ('SUCCEEDED', 'REJECTED')),
    CONSTRAINT ck_iam_oauth_client_operations_status CHECK (http_status BETWEEN 200 AND 499),
    CONSTRAINT uq_iam_oauth_client_operations_actor_key UNIQUE (actor_identity_id, idempotency_key)
);

CREATE UNIQUE INDEX uq_iam_oauth_client_operations_recovery
    ON iam_oauth_client_management_operations (original_operation_id)
    WHERE operation_type = 'RECOVER' AND outcome = 'SUCCEEDED';

GRANT SELECT, INSERT ON iam_oauth_client_management_operations TO iam_app;
