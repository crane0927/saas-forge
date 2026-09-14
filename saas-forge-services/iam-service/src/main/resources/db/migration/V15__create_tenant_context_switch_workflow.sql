CREATE TABLE iam_tenant_context_switches (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    family_id UUID NOT NULL REFERENCES iam_refresh_token_families (id),
    idempotency_key UUID NOT NULL,
    target_membership_id UUID NOT NULL,
    target_fingerprint BYTEA NOT NULL,
    expected_context_version BIGINT NOT NULL,
    switch_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_iam_tenant_context_switches_family_key UNIQUE (family_id, idempotency_key),
    CONSTRAINT ck_iam_tenant_context_switches_idempotency_uuidv7
        CHECK (uuid_extract_version(idempotency_key) = 7),
    CONSTRAINT ck_iam_tenant_context_switches_target_uuidv7
        CHECK (uuid_extract_version(target_membership_id) = 7),
    CONSTRAINT ck_iam_tenant_context_switches_target_fingerprint
        CHECK (octet_length(target_fingerprint) = 32),
    CONSTRAINT ck_iam_tenant_context_switches_context_version
        CHECK (expected_context_version >= 0),
    CONSTRAINT ck_iam_tenant_context_switches_status
        CHECK (switch_status IN ('PENDING', 'NO_OP', 'CURRENT_REJECTED', 'TARGET_REJECTED')),
    CONSTRAINT ck_iam_tenant_context_switches_completion CHECK (
        (switch_status = 'PENDING' AND completed_at IS NULL)
        OR (switch_status <> 'PENDING' AND completed_at IS NOT NULL AND completed_at >= created_at)
    )
);

CREATE UNIQUE INDEX uq_iam_tenant_context_switches_one_pending_family
    ON iam_tenant_context_switches (family_id)
    WHERE switch_status = 'PENDING';

GRANT SELECT, INSERT, UPDATE ON iam_tenant_context_switches TO iam_app;
