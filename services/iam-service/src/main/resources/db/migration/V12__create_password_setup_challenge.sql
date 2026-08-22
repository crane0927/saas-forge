CREATE TABLE iam_password_setup_challenges (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    token_digest BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    invalidated_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    idempotency_key UUID,
    request_fingerprint BYTEA,
    credential_id UUID REFERENCES iam_credentials (id),
    completed_status SMALLINT,
    CONSTRAINT uq_iam_password_setup_challenge_digest UNIQUE (token_digest),
    CONSTRAINT ck_iam_password_setup_challenge_id_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_iam_password_setup_challenge_digest CHECK (octet_length(token_digest) = 32),
    CONSTRAINT ck_iam_password_setup_challenge_lifetime CHECK (expires_at = issued_at + INTERVAL '24 hours'),
    CONSTRAINT ck_iam_password_setup_challenge_invalidation CHECK (
        invalidated_at IS NULL OR (invalidated_at >= issued_at AND consumed_at IS NULL)
    ),
    CONSTRAINT ck_iam_password_setup_challenge_completion CHECK (
        (consumed_at IS NULL AND idempotency_key IS NULL AND request_fingerprint IS NULL
            AND credential_id IS NULL AND completed_status IS NULL)
        OR
        (consumed_at >= issued_at AND invalidated_at IS NULL
            AND uuid_extract_version(idempotency_key) = 7
            AND octet_length(request_fingerprint) = 32
            AND credential_id IS NOT NULL AND completed_status = 204)
    )
);

CREATE UNIQUE INDEX uq_iam_password_setup_one_open_challenge
    ON iam_password_setup_challenges (identity_id)
    WHERE invalidated_at IS NULL AND consumed_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON iam_password_setup_challenges TO iam_app;
