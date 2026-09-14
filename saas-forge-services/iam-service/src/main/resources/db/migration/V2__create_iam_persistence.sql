CREATE TABLE iam_identities (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    normalized_email TEXT NOT NULL,
    display_name VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_iam_identities_normalized_email CHECK (
        normalized_email = lower(btrim(normalized_email))
        AND octet_length(normalized_email) = char_length(normalized_email)
    ),
    CONSTRAINT ck_iam_identities_display_name CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 200),
    CONSTRAINT uq_iam_identities_normalized_email UNIQUE (normalized_email)
);

CREATE TABLE iam_credentials (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    credential_type TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_credentials_type CHECK (credential_type IN ('INITIAL_PLATFORM_PASSWORD', 'PASSWORD')),
    CONSTRAINT ck_iam_credentials_argon2id CHECK (password_hash LIKE '$argon2id$v=19$m=19456,t=2,p=1$%'),
    CONSTRAINT ck_iam_credentials_expiry CHECK (
        (credential_type = 'INITIAL_PLATFORM_PASSWORD' AND expires_at IS NOT NULL)
        OR (credential_type = 'PASSWORD' AND expires_at IS NULL)
    ),
    CONSTRAINT ck_iam_credentials_lifecycle CHECK (
        (expires_at IS NULL OR expires_at >= issued_at)
        AND (invalidated_at IS NULL OR invalidated_at >= issued_at)
    )
);

CREATE UNIQUE INDEX uq_iam_credentials_one_valid_password
    ON iam_credentials (identity_id)
    WHERE credential_type = 'PASSWORD' AND invalidated_at IS NULL;

CREATE TABLE iam_refresh_token_families (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    membership_id UUID,
    tenant_id UUID,
    last_used_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_refresh_token_families_context CHECK ((membership_id IS NULL) = (tenant_id IS NULL)),
    CONSTRAINT ck_iam_refresh_token_families_lifetime CHECK (absolute_expires_at > last_used_at),
    CONSTRAINT ck_iam_refresh_token_families_revocation CHECK (revoked_at IS NULL OR revoked_at >= last_used_at)
);

CREATE TABLE iam_refresh_tokens (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    family_id UUID NOT NULL REFERENCES iam_refresh_token_families (id),
    token_digest BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_refresh_tokens_digest CHECK (octet_length(token_digest) = 32),
    CONSTRAINT ck_iam_refresh_tokens_consumption CHECK (consumed_at IS NULL OR consumed_at >= issued_at),
    CONSTRAINT uq_iam_refresh_tokens_digest UNIQUE (token_digest)
);

CREATE TABLE iam_oauth_clients (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    display_name VARCHAR(200) NOT NULL,
    allowed_scopes TEXT[] NOT NULL,
    client_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_oauth_clients_display_name CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 200),
    CONSTRAINT ck_iam_oauth_clients_scopes CHECK (
        cardinality(allowed_scopes) > 0
        AND array_position(allowed_scopes, NULL) IS NULL
        AND allowed_scopes <@ ARRAY['runtime:read', 'runtime:quota:write']::TEXT[]
    ),
    CONSTRAINT ck_iam_oauth_clients_status CHECK (client_status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_iam_oauth_clients_revocation CHECK (
        (client_status = 'ACTIVE' AND revoked_at IS NULL)
        OR (client_status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE TABLE iam_oauth_client_secrets (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    client_id UUID NOT NULL REFERENCES iam_oauth_clients (id),
    secret_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_oauth_client_secrets_digest CHECK (octet_length(secret_digest) = 32),
    CONSTRAINT ck_iam_oauth_client_secrets_lifecycle CHECK (
        (valid_until IS NULL OR valid_until >= created_at)
        AND (revoked_at IS NULL OR revoked_at >= created_at)
    ),
    CONSTRAINT uq_iam_oauth_client_secrets_digest UNIQUE (secret_digest)
);

CREATE TABLE iam_signing_keys (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    kid TEXT NOT NULL,
    key_version_reference TEXT NOT NULL,
    public_jwk_modulus TEXT NOT NULL,
    public_jwk_exponent TEXT NOT NULL,
    key_status TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    retire_after TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_iam_signing_keys_kid UNIQUE (kid),
    CONSTRAINT ck_iam_signing_keys_identifiers CHECK (
        char_length(btrim(kid)) > 0
        AND char_length(btrim(key_version_reference)) > 0
        AND char_length(btrim(public_jwk_modulus)) > 0
        AND char_length(btrim(public_jwk_exponent)) > 0
    ),
    CONSTRAINT ck_iam_signing_keys_status CHECK (
        key_status IN ('PUBLISHED', 'ACTIVE', 'RETIRING', 'RETIRED', 'REVOKED')
    ),
    CONSTRAINT ck_iam_signing_keys_lifecycle CHECK (
        (activated_at IS NULL OR published_at IS NOT NULL)
        AND (retire_after IS NULL OR activated_at IS NOT NULL)
        AND (retired_at IS NULL OR retire_after IS NOT NULL)
        AND (revoked_at IS NULL OR key_status = 'REVOKED')
    )
);

CREATE UNIQUE INDEX uq_iam_signing_keys_one_active
    ON iam_signing_keys ((key_status = 'ACTIVE'))
    WHERE key_status = 'ACTIVE';

GRANT SELECT, INSERT, UPDATE ON iam_identities TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_credentials TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_refresh_token_families TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_refresh_tokens TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_oauth_clients TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_oauth_client_secrets TO iam_app;
GRANT SELECT, INSERT, UPDATE ON iam_signing_keys TO iam_app;
