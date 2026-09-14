ALTER TABLE password_setup_delivery_work_items
    DROP CONSTRAINT ck_password_setup_work_item_status,
    DROP CONSTRAINT ck_password_setup_work_item_completion,
    ADD CONSTRAINT ck_password_setup_work_item_status
        CHECK (work_status IN ('PENDING', 'COMPLETED', 'SUPERSEDED')),
    ADD CONSTRAINT ck_password_setup_work_item_completion
        CHECK ((work_status = 'PENDING') = (completed_at IS NULL));

CREATE TABLE administrator_password_setup_workflows (
    workflow_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    actor_identity_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    administrator_identity_id UUID NOT NULL,
    delivery_request_id UUID NOT NULL UNIQUE,
    trace_id CHAR(32),
    outcome_code TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    recovery_exhausted_at TIMESTAMPTZ,
    last_failure TEXT,
    CONSTRAINT uq_administrator_password_setup_caller_key
        UNIQUE (actor_identity_id, idempotency_key),
    CONSTRAINT ck_administrator_password_setup_ids CHECK (
        uuid_extract_version(workflow_id) = 7
        AND uuid_extract_version(tenant_id) = 7
        AND uuid_extract_version(actor_identity_id) = 7
        AND uuid_extract_version(idempotency_key) = 7
        AND uuid_extract_version(administrator_identity_id) = 7
        AND uuid_extract_version(delivery_request_id) = 7),
    CONSTRAINT ck_administrator_password_setup_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_administrator_password_setup_trace
        CHECK (trace_id IS NULL OR trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    CONSTRAINT ck_administrator_password_setup_outcome CHECK (
        outcome_code IS NULL OR outcome_code IN ('SUCCESS', 'IDENTITY_CREDENTIAL_RECOVERY_REQUIRED')),
    CONSTRAINT ck_administrator_password_setup_completion CHECK (
        (outcome_code IS NULL AND completed_at IS NULL AND expires_at IS NULL)
        OR (outcome_code IS NOT NULL AND completed_at IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT ck_administrator_password_setup_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_administrator_password_setup_lease CHECK (
        (lease_owner IS NULL) = (lease_until IS NULL))
);

CREATE INDEX ix_administrator_password_setup_recovery
    ON administrator_password_setup_workflows (next_attempt_at, created_at, workflow_id)
    WHERE outcome_code IS NULL AND recovery_exhausted_at IS NULL;

ALTER TABLE administrator_password_setup_workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE administrator_password_setup_workflows FORCE ROW LEVEL SECURITY;

CREATE POLICY administrator_password_setup_runtime_access ON administrator_password_setup_workflows
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY administrator_password_setup_migration_access ON administrator_password_setup_workflows
    FOR ALL TO tenant_access_migrator USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON administrator_password_setup_workflows TO tenant_access_app;

CREATE FUNCTION claim_administrator_password_setup_workflow(
    requested_workflow_id UUID,
    requested_claimant TEXT,
    requested_at TIMESTAMPTZ,
    requested_until TIMESTAMPTZ)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    target_tenant_id UUID;
BEGIN
    SELECT workflow.tenant_id INTO target_tenant_id
    FROM public.administrator_password_setup_workflows workflow
    WHERE workflow.workflow_id = requested_workflow_id;
    IF target_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(target_tenant_id::TEXT, 47));

    UPDATE public.administrator_password_setup_workflows workflow
    SET lease_owner = requested_claimant,
        lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1,
        recovery_exhausted_at = NULL
    WHERE workflow.workflow_id = requested_workflow_id
      AND workflow.outcome_code IS NULL
      AND workflow.next_attempt_at <= requested_at
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows leased
          WHERE leased.tenant_id = workflow.tenant_id
            AND leased.workflow_id <> workflow.workflow_id
            AND leased.lease_until > requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows earlier
          WHERE earlier.tenant_id = workflow.tenant_id
            AND earlier.outcome_code IS NULL
            AND earlier.recovery_exhausted_at IS NULL
            AND (earlier.created_at, earlier.workflow_id) < (workflow.created_at, workflow.workflow_id))
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_administrator_initialization_workflows initialization
          WHERE initialization.tenant_id = workflow.tenant_id
            AND initialization.lease_until > requested_at)
    RETURNING workflow.tenant_id INTO target_tenant_id;

    IF target_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;

    UPDATE public.password_setup_delivery_work_items work
    SET work_status = 'SUPERSEDED', completed_at = requested_at
    WHERE work.tenant_id = target_tenant_id AND work.work_status = 'PENDING';

    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN requested_workflow_id;
END;
$$;

CREATE FUNCTION claim_next_administrator_password_setup_workflow(
    requested_claimant TEXT,
    requested_at TIMESTAMPTZ,
    requested_until TIMESTAMPTZ)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    selected_workflow_id UUID;
    target_tenant_id UUID;
BEGIN
    SELECT workflow.workflow_id, workflow.tenant_id
    INTO selected_workflow_id, target_tenant_id
    FROM public.administrator_password_setup_workflows workflow
    WHERE workflow.outcome_code IS NULL
      AND workflow.recovery_exhausted_at IS NULL
      AND workflow.next_attempt_at <= requested_at
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows leased
          WHERE leased.tenant_id = workflow.tenant_id
            AND leased.workflow_id <> workflow.workflow_id
            AND leased.lease_until > requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows earlier
          WHERE earlier.tenant_id = workflow.tenant_id
            AND earlier.outcome_code IS NULL
            AND earlier.recovery_exhausted_at IS NULL
            AND (earlier.created_at, earlier.workflow_id) < (workflow.created_at, workflow.workflow_id))
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_administrator_initialization_workflows initialization
          WHERE initialization.tenant_id = workflow.tenant_id
            AND initialization.lease_until > requested_at)
    ORDER BY workflow.next_attempt_at, workflow.created_at, workflow.workflow_id
    FOR UPDATE SKIP LOCKED
    LIMIT 1;

    IF selected_workflow_id IS NULL THEN
        RETURN NULL;
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(target_tenant_id::TEXT, 47));

    UPDATE public.administrator_password_setup_workflows workflow
    SET lease_owner = requested_claimant,
        lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1
    WHERE workflow.workflow_id = selected_workflow_id
      AND workflow.outcome_code IS NULL
      AND workflow.recovery_exhausted_at IS NULL
      AND workflow.next_attempt_at <= requested_at
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows leased
          WHERE leased.tenant_id = workflow.tenant_id
            AND leased.workflow_id <> workflow.workflow_id
            AND leased.lease_until > requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows earlier
          WHERE earlier.tenant_id = workflow.tenant_id
            AND earlier.outcome_code IS NULL
            AND earlier.recovery_exhausted_at IS NULL
            AND (earlier.created_at, earlier.workflow_id) < (workflow.created_at, workflow.workflow_id))
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_administrator_initialization_workflows initialization
          WHERE initialization.tenant_id = workflow.tenant_id
            AND initialization.lease_until > requested_at)
    RETURNING workflow.tenant_id INTO target_tenant_id;

    IF target_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;

    UPDATE public.password_setup_delivery_work_items work
    SET work_status = 'SUPERSEDED', completed_at = requested_at
    WHERE work.tenant_id = target_tenant_id AND work.work_status = 'PENDING';

    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN selected_workflow_id;
END;
$$;

REVOKE ALL ON FUNCTION claim_administrator_password_setup_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_next_administrator_password_setup_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_administrator_password_setup_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    TO tenant_access_app;
GRANT EXECUTE ON FUNCTION claim_next_administrator_password_setup_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    TO tenant_access_app;

CREATE OR REPLACE FUNCTION claim_tenant_admin_initialization_workflow(
    requested_workflow_id UUID,
    requested_claimant TEXT,
    requested_at TIMESTAMPTZ,
    requested_until TIMESTAMPTZ)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    target_tenant_id UUID;
BEGIN
    SELECT workflow.tenant_id INTO target_tenant_id
    FROM public.tenant_administrator_initialization_workflows workflow
    WHERE workflow.workflow_id = requested_workflow_id;
    IF target_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(target_tenant_id::TEXT, 47));

    UPDATE public.tenant_administrator_initialization_workflows workflow
    SET lease_owner = requested_claimant,
        lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1,
        recovery_exhausted_at = NULL
    WHERE workflow.workflow_id = requested_workflow_id
      AND workflow.next_attempt_at <= requested_at
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
      AND (workflow.outcome_code IS NULL OR (
          workflow.outcome_code = 'SUCCESS' AND EXISTS (
              SELECT 1 FROM public.password_setup_delivery_work_items work
              WHERE work.workflow_id = workflow.workflow_id AND work.work_status = 'PENDING')))
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows resend
          WHERE resend.tenant_id = workflow.tenant_id AND resend.lease_until > requested_at)
    RETURNING workflow.tenant_id INTO target_tenant_id;

    IF target_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN requested_workflow_id;
END;
$$;

CREATE OR REPLACE FUNCTION claim_next_tenant_admin_initialization_workflow(
    requested_claimant TEXT,
    requested_at TIMESTAMPTZ,
    requested_until TIMESTAMPTZ)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    selected_workflow_id UUID;
    target_tenant_id UUID;
BEGIN
    SELECT workflow.workflow_id, workflow.tenant_id
    INTO selected_workflow_id, target_tenant_id
    FROM public.tenant_administrator_initialization_workflows workflow
    WHERE workflow.next_attempt_at <= requested_at
      AND workflow.recovery_exhausted_at IS NULL
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
      AND (workflow.outcome_code IS NULL OR (
          workflow.outcome_code = 'SUCCESS' AND EXISTS (
              SELECT 1 FROM public.password_setup_delivery_work_items work
              WHERE work.workflow_id = workflow.workflow_id AND work.work_status = 'PENDING')))
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_administrator_initialization_workflows leased
          WHERE leased.tenant_id = workflow.tenant_id AND leased.lease_until > requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows resend
          WHERE resend.tenant_id = workflow.tenant_id AND resend.lease_until > requested_at)
    ORDER BY workflow.next_attempt_at, workflow.created_at, workflow.workflow_id
    FOR UPDATE SKIP LOCKED
    LIMIT 1;

    IF selected_workflow_id IS NULL THEN
        RETURN NULL;
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(target_tenant_id::TEXT, 47));

    UPDATE public.tenant_administrator_initialization_workflows workflow
    SET lease_owner = requested_claimant,
        lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1
    WHERE workflow.workflow_id = selected_workflow_id
      AND workflow.next_attempt_at <= requested_at
      AND workflow.recovery_exhausted_at IS NULL
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_administrator_initialization_workflows leased
          WHERE leased.tenant_id = workflow.tenant_id
            AND leased.workflow_id <> workflow.workflow_id
            AND leased.lease_until > requested_at)
      AND NOT EXISTS (
          SELECT 1 FROM public.administrator_password_setup_workflows resend
          WHERE resend.tenant_id = workflow.tenant_id AND resend.lease_until > requested_at)
    RETURNING workflow.tenant_id INTO target_tenant_id;

    IF target_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN selected_workflow_id;
END;
$$;

REVOKE ALL ON FUNCTION claim_tenant_admin_initialization_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_next_tenant_admin_initialization_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_tenant_admin_initialization_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    TO tenant_access_app;
GRANT EXECUTE ON FUNCTION claim_next_tenant_admin_initialization_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    TO tenant_access_app;
