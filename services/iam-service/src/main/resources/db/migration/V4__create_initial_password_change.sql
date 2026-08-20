ALTER TABLE iam_refresh_token_families
    ADD COLUMN initial_credential_id UUID REFERENCES iam_credentials (id),
    ADD CONSTRAINT ck_iam_refresh_token_families_initial_credential CHECK (
        (family_purpose = 'INITIAL_PASSWORD_CHANGE') = (initial_credential_id IS NOT NULL)
    );

GRANT SELECT, INSERT, UPDATE ON iam_refresh_token_families TO iam_app;
