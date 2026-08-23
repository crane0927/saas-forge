ALTER TABLE tenant_administrator_initialization_workflows
    ADD COLUMN workflow_state TEXT NOT NULL DEFAULT 'PREPARED',
    ADD COLUMN administrator_identity_id UUID,
    ADD COLUMN credential_disposition TEXT,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    ADD COLUMN lease_owner TEXT,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN last_failure TEXT;

UPDATE tenant_administrator_initialization_workflows
SET workflow_state = CASE WHEN outcome_code = 'SUCCESS' THEN 'SUCCEEDED'
                          WHEN outcome_code IS NOT NULL THEN 'FAILED'
                          ELSE 'PREPARED' END,
    next_attempt_at = created_at;

ALTER TABLE tenant_administrator_initialization_workflows
    ADD CONSTRAINT ck_tenant_admin_initialization_state CHECK (
        workflow_state IN ('PREPARED', 'IDENTITY_READY', 'QUOTA_CONSUMED', 'ACTIVATING',
                           'COMPENSATING', 'SUCCEEDED', 'FAILED')),
    ADD CONSTRAINT ck_tenant_admin_initialization_identity CHECK (
        (workflow_state = 'PREPARED' AND administrator_identity_id IS NULL AND credential_disposition IS NULL)
        OR (workflow_state <> 'PREPARED' AND administrator_identity_id IS NOT NULL
            AND credential_disposition IN ('PASSWORD_READY', 'SETUP_ALLOWED'))
        OR (workflow_state = 'FAILED')),
    ADD CONSTRAINT ck_tenant_admin_initialization_attempts CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_tenant_admin_initialization_lease CHECK (
        (lease_owner IS NULL) = (lease_until IS NULL)),
    ADD CONSTRAINT ck_tenant_admin_initialization_state_outcome CHECK (
        (workflow_state = 'SUCCEEDED') = (outcome_code = 'SUCCESS')
        AND (workflow_state = 'FAILED') = (outcome_code IS NOT NULL AND outcome_code <> 'SUCCESS'));

CREATE INDEX ix_tenant_admin_initialization_recovery
    ON tenant_administrator_initialization_workflows (next_attempt_at, created_at)
    WHERE outcome_code IS NULL OR outcome_code = 'SUCCESS';

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
        attempt_count = workflow.attempt_count + 1
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

CREATE OR REPLACE FUNCTION claim_next_tenant_access_outbox_event(
    requested_claimant TEXT,
    requested_at TIMESTAMPTZ,
    requested_until TIMESTAMPTZ)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    selected_event_id UUID;
    target_tenant_id UUID;
BEGIN
    WITH candidate AS (
        SELECT current.event_id
        FROM public.tenant_access_outbox_events current
        WHERE current.published_at IS NULL
          AND current.next_attempt_at <= requested_at
          AND (current.claimed_until IS NULL OR current.claimed_until <= requested_at)
          AND NOT EXISTS (
              SELECT 1 FROM public.tenant_access_outbox_events earlier
              WHERE earlier.ordering_key = current.ordering_key
                AND earlier.published_at IS NULL
                AND (earlier.occurred_at, earlier.event_id) < (current.occurred_at, current.event_id))
        ORDER BY current.occurred_at, current.event_id
        FOR UPDATE SKIP LOCKED
        LIMIT 1
    )
    UPDATE public.tenant_access_outbox_events event
    SET claimed_by = requested_claimant,
        claimed_until = requested_until,
        attempt_count = event.attempt_count + 1
    FROM candidate
    WHERE event.event_id = candidate.event_id
    RETURNING event.event_id, event.tenant_id INTO selected_event_id, target_tenant_id;

    IF selected_event_id IS NULL THEN
        RETURN NULL;
    END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN selected_event_id;
END;
$$;

REVOKE ALL ON FUNCTION claim_next_tenant_access_outbox_event(TEXT, TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_next_tenant_access_outbox_event(TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    TO tenant_access_app;
