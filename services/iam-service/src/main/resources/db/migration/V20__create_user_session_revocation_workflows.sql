CREATE TABLE iam_user_session_revocations (
    revocation_request_id UUID PRIMARY KEY REFERENCES iam_revocation_fences (revocation_request_id),
    revocation_status TEXT NOT NULL,
    cursor_family_id UUID,
    revoked_family_count BIGINT NOT NULL DEFAULT 0,
    revoked_jti_count BIGINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    recovery_exhausted_at TIMESTAMPTZ,
    last_failure TEXT,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_user_session_revocations_status
        CHECK (revocation_status IN ('PENDING', 'COMPLETED', 'RECOVERY_REQUIRED')),
    CONSTRAINT ck_iam_user_session_revocations_counts
        CHECK (revoked_family_count >= 0 AND revoked_jti_count >= 0 AND attempt_count >= 0
            AND fencing_token >= 0),
    CONSTRAINT ck_iam_user_session_revocations_lease
        CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_iam_user_session_revocations_failure
        CHECK (last_failure IS NULL OR length(last_failure) <= 100),
    CONSTRAINT ck_iam_user_session_revocations_completion CHECK (
        (revocation_status = 'COMPLETED' AND completed_at IS NOT NULL AND recovery_exhausted_at IS NULL)
        OR (revocation_status = 'RECOVERY_REQUIRED' AND completed_at IS NULL AND recovery_exhausted_at IS NOT NULL)
        OR (revocation_status = 'PENDING' AND completed_at IS NULL AND recovery_exhausted_at IS NULL)
    )
);

CREATE INDEX ix_iam_user_session_revocations_claimable
    ON iam_user_session_revocations (next_attempt_at, revocation_request_id)
    WHERE revocation_status = 'PENDING';

CREATE TABLE iam_user_session_fence_releases (
    release_request_id UUID PRIMARY KEY,
    revocation_request_id UUID NOT NULL REFERENCES iam_user_session_revocations (revocation_request_id),
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    released_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_iam_user_session_fence_releases_request_uuidv7
        CHECK (uuid_extract_version(release_request_id) = 7),
    CONSTRAINT ck_iam_user_session_fence_releases_target
        CHECK (target_type IN ('TENANT', 'MEMBERSHIP') AND uuid_extract_version(target_id) = 7),
    CONSTRAINT uq_iam_user_session_fence_release_generation UNIQUE (revocation_request_id)
);

GRANT SELECT, INSERT, UPDATE ON iam_user_session_revocations TO iam_app;
GRANT SELECT, INSERT ON iam_user_session_fence_releases TO iam_app;
