ALTER TABLE iam_oauth_clients
    DROP CONSTRAINT ck_iam_oauth_clients_scopes;

ALTER TABLE iam_oauth_clients
    ADD CONSTRAINT ck_iam_oauth_clients_scopes CHECK (
        cardinality(allowed_scopes) > 0
        AND array_position(allowed_scopes, NULL) IS NULL
        AND allowed_scopes <@ ARRAY[
            'runtime:read',
            'runtime:quota:write',
            'tenant-access:membership:read',
            'tenant-access:tenant:read',
            'iam:identity:write',
            'iam:password-setup:write',
            'iam:platform-role:read',
            'entitlement:quota:write'
        ]::TEXT[]
    );
