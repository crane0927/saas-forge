CREATE INDEX ix_iam_access_token_issuances_revoked_expiry
    ON iam_access_token_issuances (expires_at, jti)
    WHERE revoked_at IS NOT NULL;
