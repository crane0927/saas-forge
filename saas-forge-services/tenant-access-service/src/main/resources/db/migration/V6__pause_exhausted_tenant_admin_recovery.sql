ALTER TABLE tenant_administrator_initialization_workflows
    ADD COLUMN recovery_exhausted_at TIMESTAMPTZ;

CREATE INDEX ix_tenant_admin_initialization_recovery_exhausted
    ON tenant_administrator_initialization_workflows (recovery_exhausted_at)
    WHERE recovery_exhausted_at IS NOT NULL;

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
    WITH candidate AS (
        SELECT workflow.workflow_id
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
              WHERE leased.tenant_id = workflow.tenant_id
                AND leased.lease_until > requested_at)
        ORDER BY workflow.next_attempt_at, workflow.created_at, workflow.workflow_id
        FOR UPDATE SKIP LOCKED
        LIMIT 1
    )
    UPDATE public.tenant_administrator_initialization_workflows workflow
    SET lease_owner = requested_claimant,
        lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1
    FROM candidate
    WHERE workflow.workflow_id = candidate.workflow_id
    RETURNING workflow.workflow_id, workflow.tenant_id
    INTO selected_workflow_id, target_tenant_id;

    IF selected_workflow_id IS NULL THEN
        RETURN NULL;
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN selected_workflow_id;
END;
$$;

REVOKE ALL ON FUNCTION claim_tenant_admin_initialization_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_next_tenant_admin_initialization_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_tenant_admin_initialization_workflow(UUID, TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    TO tenant_access_app;
GRANT EXECUTE ON FUNCTION claim_next_tenant_admin_initialization_workflow(TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    TO tenant_access_app;
