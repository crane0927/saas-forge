CREATE TABLE iam_revocation_fences (
    revocation_request_id UUID PRIMARY KEY,
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    membership_id UUID,
    fence_status TEXT NOT NULL,
    established_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    CONSTRAINT ck_iam_revocation_fences_request_uuidv7
        CHECK (uuid_extract_version(revocation_request_id) = 7),
    CONSTRAINT ck_iam_revocation_fences_target_uuidv7
        CHECK (uuid_extract_version(target_id) = 7
            AND uuid_extract_version(tenant_id) = 7
            AND (membership_id IS NULL OR uuid_extract_version(membership_id) = 7)),
    CONSTRAINT ck_iam_revocation_fences_target_shape CHECK (
        (target_type = 'TENANT' AND membership_id IS NULL AND target_id = tenant_id)
        OR (target_type = 'MEMBERSHIP' AND membership_id IS NOT NULL AND target_id = membership_id)
    ),
    CONSTRAINT ck_iam_revocation_fences_status
        CHECK (fence_status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_iam_revocation_fences_release CHECK (
        (fence_status = 'ACTIVE' AND released_at IS NULL)
        OR (fence_status = 'RELEASED' AND released_at IS NOT NULL AND released_at >= established_at)
    )
);

CREATE UNIQUE INDEX uq_iam_revocation_fences_active_target
    ON iam_revocation_fences (target_type, target_id)
    WHERE fence_status = 'ACTIVE';

CREATE INDEX ix_iam_revocation_fences_active_tenant
    ON iam_revocation_fences (tenant_id, target_type)
    WHERE fence_status = 'ACTIVE';

GRANT SELECT, INSERT, UPDATE ON iam_revocation_fences TO iam_app;
