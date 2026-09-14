ALTER TABLE iam_signing_keys
    ADD COLUMN max_issued_token_ttl_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN retiring_at TIMESTAMPTZ;

UPDATE iam_signing_keys
SET retiring_at = retire_after - INTERVAL '30 minutes'
WHERE retire_after IS NOT NULL;

ALTER TABLE iam_signing_keys
    ADD CONSTRAINT ck_iam_signing_keys_max_issued_token_ttl
        CHECK (max_issued_token_ttl_seconds >= 0),
    ADD CONSTRAINT ck_iam_signing_keys_retiring_window
        CHECK (
            (retiring_at IS NULL OR activated_at IS NOT NULL)
            AND (retire_after IS NULL OR retiring_at IS NOT NULL)
            AND (retire_after IS NULL OR retire_after >= retiring_at + INTERVAL '30 minutes')
        );
