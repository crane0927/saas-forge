CREATE TABLE tenant_lifecycle_workflows (
    workflow_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    lifecycle_action TEXT NOT NULL,
    revocation_request_id UUID NOT NULL,
    release_request_id UUID,
    workflow_status TEXT NOT NULL DEFAULT 'PENDING',
    fence_established BOOLEAN NOT NULL DEFAULT FALSE,
    revocation_call_started BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_family_count BIGINT NOT NULL DEFAULT 0,
    revoked_jti_count BIGINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    recovery_started_at TIMESTAMPTZ,
    iam_recovery_confirmed_at TIMESTAMPTZ,
    recovery_exhausted_at TIMESTAMPTZ,
    last_failure TEXT,
    response_body JSONB,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor_identity_id, idempotency_key),
    CONSTRAINT ck_tenant_lifecycle_workflow_uuidv7 CHECK (uuid_extract_version(workflow_id) = 7),
    CONSTRAINT ck_tenant_lifecycle_actor_uuidv7 CHECK (uuid_extract_version(actor_identity_id) = 7),
    CONSTRAINT ck_tenant_lifecycle_key_uuidv7 CHECK (uuid_extract_version(idempotency_key) = 7),
    CONSTRAINT ck_tenant_lifecycle_revocation_uuidv7 CHECK (uuid_extract_version(revocation_request_id) = 7),
    CONSTRAINT ck_tenant_lifecycle_release_uuidv7
        CHECK (release_request_id IS NULL OR uuid_extract_version(release_request_id) = 7),
    CONSTRAINT ck_tenant_lifecycle_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_lifecycle_action CHECK (lifecycle_action IN ('SUSPEND', 'RESUME')),
    CONSTRAINT ck_tenant_lifecycle_status
        CHECK (workflow_status IN ('PENDING', 'COMPLETED', 'RETRY_REQUIRED', 'RECOVERY_REQUIRED')),
    CONSTRAINT ck_tenant_lifecycle_action_requests CHECK (
        (lifecycle_action = 'SUSPEND' AND release_request_id IS NULL)
        OR (lifecycle_action = 'RESUME' AND release_request_id IS NOT NULL)),
    CONSTRAINT ck_tenant_lifecycle_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_tenant_lifecycle_counts
        CHECK (revoked_family_count >= 0 AND revoked_jti_count >= 0 AND attempt_count >= 0),
    CONSTRAINT ck_tenant_lifecycle_completion CHECK (
        (workflow_status = 'COMPLETED') = (completed_at IS NOT NULL)
        AND (workflow_status = 'COMPLETED') = (response_body IS NOT NULL)),
    CONSTRAINT ck_tenant_lifecycle_recovery CHECK (
        iam_recovery_confirmed_at IS NULL OR recovery_started_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_tenant_lifecycle_workflow_active
    ON tenant_lifecycle_workflows (tenant_id)
    WHERE workflow_status IN ('PENDING', 'RECOVERY_REQUIRED');

CREATE INDEX ix_tenant_lifecycle_workflow_recovery
    ON tenant_lifecycle_workflows (next_attempt_at, workflow_id)
    WHERE workflow_status = 'PENDING';

CREATE TABLE tenant_suspension_recovery_idempotency (
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    workflow_id UUID NOT NULL REFERENCES tenant_lifecycle_workflows (workflow_id),
    request_fingerprint CHAR(64) NOT NULL,
    response_body JSONB,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor_identity_id, idempotency_key),
    CONSTRAINT ck_tenant_suspension_recovery_actor_uuidv7 CHECK (uuid_extract_version(actor_identity_id) = 7),
    CONSTRAINT ck_tenant_suspension_recovery_key_uuidv7 CHECK (uuid_extract_version(idempotency_key) = 7),
    CONSTRAINT ck_tenant_suspension_recovery_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_suspension_recovery_completion
        CHECK ((response_body IS NULL) = (completed_at IS NULL))
);

CREATE OR REPLACE FUNCTION claim_tenant_lifecycle_workflow(
    requested_workflow_id UUID, requested_claimant TEXT, requested_at TIMESTAMPTZ,
    requested_until TIMESTAMPTZ, requested_maximum_attempts INTEGER)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE target_tenant_id UUID;
BEGIN
    UPDATE public.tenant_lifecycle_workflows workflow
    SET workflow_status = CASE WHEN workflow.fence_established OR workflow.revocation_call_started
                               THEN 'RECOVERY_REQUIRED' ELSE 'RETRY_REQUIRED' END,
        recovery_exhausted_at = requested_at, lease_owner = NULL, lease_until = NULL,
        last_failure = 'RECOVERY_ATTEMPT_LIMIT_REACHED'
    WHERE workflow.workflow_id = requested_workflow_id
      AND workflow.lifecycle_action = 'SUSPEND' AND workflow.workflow_status = 'PENDING'
      AND workflow.attempt_count >= requested_maximum_attempts
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at);

    UPDATE public.tenant_lifecycle_workflows workflow
    SET lease_owner = requested_claimant, lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1, fencing_token = workflow.fencing_token + 1
    WHERE workflow.workflow_id = requested_workflow_id AND workflow.workflow_status = 'PENDING'
      AND (workflow.lifecycle_action = 'RESUME' OR workflow.attempt_count < requested_maximum_attempts)
      AND workflow.next_attempt_at <= requested_at
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
    RETURNING workflow.tenant_id INTO target_tenant_id;

    IF target_tenant_id IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN requested_workflow_id;
END;
$$;

CREATE OR REPLACE FUNCTION set_tenant_lifecycle_workflow_target(requested_workflow_id UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE target_tenant_id UUID;
BEGIN
    SELECT workflow.tenant_id INTO target_tenant_id
    FROM public.tenant_lifecycle_workflows workflow
    WHERE workflow.workflow_id = requested_workflow_id;
    IF target_tenant_id IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN target_tenant_id;
END;
$$;

CREATE OR REPLACE FUNCTION claim_next_tenant_lifecycle_workflow(
    requested_claimant TEXT, requested_at TIMESTAMPTZ, requested_until TIMESTAMPTZ,
    requested_maximum_attempts INTEGER)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE selected_workflow_id UUID; target_tenant_id UUID;
BEGIN
    UPDATE public.tenant_lifecycle_workflows workflow
    SET workflow_status = CASE WHEN workflow.fence_established OR workflow.revocation_call_started
                               THEN 'RECOVERY_REQUIRED' ELSE 'RETRY_REQUIRED' END,
        recovery_exhausted_at = requested_at, lease_owner = NULL, lease_until = NULL,
        last_failure = 'RECOVERY_ATTEMPT_LIMIT_REACHED'
    WHERE workflow.lifecycle_action = 'SUSPEND' AND workflow.workflow_status = 'PENDING'
      AND workflow.attempt_count >= requested_maximum_attempts
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at);

    WITH candidate AS (
        SELECT workflow.workflow_id FROM public.tenant_lifecycle_workflows workflow
        WHERE workflow.workflow_status = 'PENDING'
          AND (workflow.lifecycle_action = 'RESUME' OR workflow.attempt_count < requested_maximum_attempts)
          AND workflow.next_attempt_at <= requested_at
          AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
        ORDER BY workflow.next_attempt_at, workflow.created_at, workflow.workflow_id
        FOR UPDATE SKIP LOCKED LIMIT 1
    )
    UPDATE public.tenant_lifecycle_workflows workflow
    SET lease_owner = requested_claimant, lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1, fencing_token = workflow.fencing_token + 1
    FROM candidate WHERE workflow.workflow_id = candidate.workflow_id
    RETURNING workflow.workflow_id, workflow.tenant_id INTO selected_workflow_id, target_tenant_id;

    IF selected_workflow_id IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN selected_workflow_id;
END;
$$;

ALTER TABLE tenant_lifecycle_workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_lifecycle_workflows FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_suspension_recovery_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_suspension_recovery_idempotency FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_lifecycle_workflow_runtime_access ON tenant_lifecycle_workflows
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_lifecycle_workflow_migration_access ON tenant_lifecycle_workflows
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

CREATE POLICY tenant_suspension_recovery_runtime_access ON tenant_suspension_recovery_idempotency
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_suspension_recovery_migration_access ON tenant_suspension_recovery_idempotency
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON tenant_lifecycle_workflows TO tenant_access_app;
GRANT SELECT, INSERT, UPDATE ON tenant_suspension_recovery_idempotency TO tenant_access_app;
REVOKE ALL ON FUNCTION claim_tenant_lifecycle_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_next_tenant_lifecycle_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION set_tenant_lifecycle_workflow_target(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_tenant_lifecycle_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)
    TO tenant_access_app;
GRANT EXECUTE ON FUNCTION claim_next_tenant_lifecycle_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)
    TO tenant_access_app;
GRANT EXECUTE ON FUNCTION set_tenant_lifecycle_workflow_target(UUID) TO tenant_access_app;
