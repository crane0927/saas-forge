ALTER TABLE iam_refresh_token_families
    ADD COLUMN context_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_iam_refresh_token_families_context_version CHECK (context_version >= 0);
