CREATE TABLE iam_password_setup_deliveries (
    caller_client_id UUID NOT NULL REFERENCES iam_oauth_clients (id),
    request_id UUID NOT NULL,
    identity_id UUID NOT NULL REFERENCES iam_identities (id),
    status TEXT NOT NULL,
    challenge_id UUID REFERENCES iam_password_setup_challenges (id),
    challenge_expires_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (caller_client_id, request_id),
    CONSTRAINT ck_iam_password_setup_delivery_caller_uuidv7
        CHECK (uuid_extract_version(caller_client_id) = 7),
    CONSTRAINT ck_iam_password_setup_delivery_request_uuidv7
        CHECK (uuid_extract_version(request_id) = 7),
    CONSTRAINT ck_iam_password_setup_delivery_status
        CHECK (status IN ('PENDING', 'DELIVERED', 'PASSWORD_READY')),
    CONSTRAINT ck_iam_password_setup_delivery_state CHECK (
        (status = 'PENDING' AND challenge_id IS NOT NULL AND challenge_expires_at IS NOT NULL
            AND completed_at IS NULL)
        OR
        (status = 'DELIVERED' AND challenge_id IS NOT NULL AND challenge_expires_at IS NOT NULL
            AND completed_at IS NOT NULL)
        OR
        (status = 'PASSWORD_READY' AND challenge_id IS NULL AND challenge_expires_at IS NULL
            AND completed_at IS NOT NULL)
    )
);

GRANT SELECT, INSERT, UPDATE ON iam_password_setup_deliveries TO iam_app;
