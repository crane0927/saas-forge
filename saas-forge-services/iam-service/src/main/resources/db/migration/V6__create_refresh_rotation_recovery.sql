ALTER TABLE iam_refresh_tokens
    ADD COLUMN rotation_key_digest BYTEA,
    ADD COLUMN recovery_expires_at TIMESTAMPTZ,
    ADD COLUMN recovered_at TIMESTAMPTZ,
    ADD COLUMN successor_token_id UUID REFERENCES iam_refresh_tokens (id),
    ADD COLUMN successor_access_jti UUID,
    ADD CONSTRAINT ck_iam_refresh_tokens_rotation_key_digest
        CHECK (rotation_key_digest IS NULL OR octet_length(rotation_key_digest) = 32),
    ADD CONSTRAINT ck_iam_refresh_tokens_rotation_recovery CHECK (
        (rotation_key_digest IS NULL AND recovery_expires_at IS NULL
            AND recovered_at IS NULL AND successor_token_id IS NULL AND successor_access_jti IS NULL)
        OR
        (rotation_key_digest IS NOT NULL AND recovery_expires_at IS NOT NULL
            AND successor_token_id IS NOT NULL
            AND recovery_expires_at >= consumed_at
            AND (recovered_at IS NULL OR recovered_at BETWEEN consumed_at AND recovery_expires_at))
    );

CREATE INDEX ix_iam_access_token_issuances_family_expiry
    ON iam_access_token_issuances (family_id, expires_at, jti);
